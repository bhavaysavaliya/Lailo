package com.lailo.application.features.payment.infrastructure.gateway;

import com.lailo.application.features.payment.config.PaymentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Development and test gateway. It behaves like a real provider - it issues a reference, decides, and signs
 * its webhooks with HMAC-SHA256 - without talking to anyone.
 *
 * <p>So that failure paths can be exercised deterministically, a reference containing {@code decline} is
 * declined. That is the only unrealistic thing about it, and it is why this class must never be the
 * configured provider outside local development and tests.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class SimulatedPaymentGateway implements PaymentGateway {

    /** Marker a test or a developer puts in a reference to force a decline. */
    private static final String DECLINE_MARKER = "decline";

    private final PaymentProperties properties;
    private final Clock clock;

    /**
     * Names itself {@code simulated}, which makes it obvious in the database when it was used by mistake.
     *
     * @return the provider name
     */
    @Override
    public String provider() {
        return "simulated";
    }

    /**
     * Mints a reference derived from the payment id, so the same attempt always maps to the same reference.
     *
     * @param request what to charge and for which order
     * @return the intent
     */
    @Override
    public Mono<GatewayIntent> createIntent(GatewayIntentRequest request) {
        String reference = "sim_" + request.paymentId().toString().replace("-", "");
        return Mono.fromSupplier(() -> GatewayIntent.builder()
                .providerReference(reference)
                .checkoutUrl(properties.simulatedCheckoutUrl() + "?ref=" + reference)
                .expiresAt(clock.instant().plus(properties.intentTtl()))
                .build());
    }

    /**
     * Approves anything that does not ask to be declined.
     *
     * @param providerReference the reference to decide on
     * @return the decision
     */
    @Override
    public Mono<GatewayOutcome> confirm(String providerReference) {
        return Mono.fromSupplier(() -> {
            if (providerReference != null
                    && providerReference.toLowerCase(Locale.ROOT).contains(DECLINE_MARKER)) {
                log.debug("Simulated gateway declining {}", providerReference);
                return GatewayOutcome.declined(providerReference, "Payment declined by your bank.");
            }
            return GatewayOutcome.approved(providerReference);
        });
    }

    /**
     * Always refunds successfully.
     *
     * @param providerReference the reference to refund
     * @param amount amount to return
     * @return an approved outcome
     */
    @Override
    public Mono<GatewayOutcome> refund(String providerReference, BigDecimal amount) {
        return Mono.fromSupplier(() -> {
            log.debug("Simulated gateway refunding {} on {}", amount, providerReference);
            return GatewayOutcome.approved(providerReference);
        });
    }

    /**
     * Verifies an HMAC-SHA256 signature over the raw body, exactly as the real providers do.
     *
     * @param payload raw request body
     * @param signature hex-encoded signature from the header, or {@code null}
     * @return {@code true} when the signature matches
     */
    @Override
    public boolean verifySignature(String payload, String signature) {
        if (payload == null || signature == null || signature.isBlank()) {
            return false;
        }
        byte[] expected = sign(payload);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(signature.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * Computes the signature of a body with the configured webhook secret. Exposed so a test can produce a
     * callback the verification accepts, instead of the verification being switched off in tests.
     *
     * @param payload raw request body
     * @return the signature bytes
     */
    public byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.webhookSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot sign webhook payload", ex);
        }
    }

    /**
     * Hex-encoded signature of a body, which is the form the header carries.
     *
     * @param payload raw request body
     * @return the signature as lower-case hex
     */
    public String signatureHeader(String payload) {
        return HexFormat.of().formatHex(sign(payload));
    }
}
