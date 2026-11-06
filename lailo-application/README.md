# lailo-application

Reactive Spring Boot 4 (WebFlux) backend for Lailo.

## Run locally

Services are installed with Homebrew and run as background services:

```bash
brew services start postgresql@17 redis kafka mongodb-community
# one-time database setup (already done on this machine)
psql -d postgres -c "CREATE ROLE lailo LOGIN PASSWORD 'lailo';" && createdb -O lailo lailo

./mvnw spring-boot:run          # activates the `dev` profile -> OTP is always 123456
```

Flyway migrates the schema on startup (`src/main/resources/db/migration`).

## Tests

| Command | What runs |
| --- | --- |
| `./mvnw test` | Pure unit tests (domain, CQRS bus), WebFlux slice tests (`WebTestClient` – the reactive counterpart of MockMvc), WireMock contract tests, context smoke test |
| `./mvnw verify` | All of the above + `*IT` tests: every command/query handler and the full HTTP flow against the embedded databases |

Repositories and Redis stores are **never mocked** – handler tests (`*HandlerIT`) run against the real embedded databases. Mockito is used only for genuinely external systems (`OtpSender` spy in `IntegrationTest`, the CQRS buses in the web slice test); external HTTP uses WireMock.

Integration tests need **no Docker and no local services**. They always run against pinned embedded infrastructure (see `src/test/java/.../support`):

| Service | Version | Library |
| --- | --- | --- |
| PostgreSQL | 18.6.0 | zonky embedded-postgres (pom property `embedded-postgres-binaries.version`) |
| MongoDB | 7.0.15 | flapdoodle Spring auto-config (`de.flapdoodle.mongodb.embedded.version` in `application-test.yaml`) |
| Redis | embedded | codemonstur embedded-redis |
| Kafka | embedded | spring-kafka-test `@EmbeddedKafka` |

`EmbeddedInfrastructureVersionIT` fails the build if any of these drift.

## Authentication design

Passwordless, unified sign-up / sign-in with email **or** mobile number (Amazon / Flipkart style):

```
GET  /api/v1/auth/captcha      -> captcha image (answer stored in Redis, single use, 2 min)
POST /api/v1/auth/otp          -> {identifier, captchaId, captchaAnswer} -> {requestId, maskedIdentifier}
POST /api/v1/auth/otp/verify   -> {requestId, otp} -> sets HttpOnly cookies; creates the account on first use
POST /api/v1/auth/refresh      -> rotates refresh token (cookie)
POST /api/v1/auth/logout       -> revokes session family, clears cookies
GET  /api/v1/users/me          -> profile (requires access token)
```

**CQRS.** Controllers only build commands/queries and hand them to `CommandBus` / `QueryBus`
(`common/cqrs`). Each use case is one handler class.

- **Write side** – PostgreSQL (`users`, `user_sessions`) via R2DBC. A handler changes state *and* appends an event to `outbox_messages` in one transaction.
- **Transactional outbox** – `OutboxRelay` publishes outbox rows to Kafka topic `lailo.auth.user-events.v1` (keyed by user id, `FOR UPDATE SKIP LOCKED` so instances don't double-send).
- **Read side** – `UserEventsListener` projects events into MongoDB `user_profiles` with idempotent upserts. `GetCurrentUserHandler` reads Mongo and falls back to Postgres while the projection catches up.
- **Redis** – captcha answers, OTP challenges (HMAC-hashed code + attempt counter, TTL), rate limits and resend cooldowns shared by all instances.

**Security controls**

- OTP: 6 digits, 5 min TTL, max 5 attempts then burned, single use (atomic delete), stored only as HMAC-SHA256 with a server key, bound to an opaque `requestId`.
- Abuse: per-IP and per-identifier rate limits, 30 s resend cooldown, captcha checked before the identifier quota is touched; identical responses for new and existing accounts (no enumeration). Redis keys use a hash of the identifier, not the raw value.
- Sessions: 15 min access JWT (HS256, only user id + roles) + 30 day opaque refresh token (only SHA-256 stored), **rotated on every use with reuse detection** – replaying an old token revokes the whole session family and emits `SessionReuseDetected`.
- Cookies: `HttpOnly`, `Secure` (except dev), access `SameSite=Lax; Path=/`, refresh `SameSite=Strict; Path=/api/v1/auth`. Tokens never appear in JSON bodies.
- CSRF: double-submit `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header on every state-changing request.
- Errors: RFC 9457 `ProblemDetail` with a stable `code`; internals never leak. Sensitive values are masked in `toString()` so the debug logging aspect can't print OTPs or tokens.

## Going to production – integration points

| Concern | Where | Change |
| --- | --- | --- |
| SMS / email delivery | `OtpSender` | Add a bean (MSG91, Twilio, SES…). `LoggingOtpSender` is only the dev fallback. |
| Real OTPs | `lailo.auth.otp.fixed-code` | Already blank by default; only `application-dev.yaml` sets `123456`. |
| Captcha | `LAILO_CAPTCHA_PROVIDER=turnstile` | `TurnstileCaptchaService` is implemented and WireMock-tested; the web app needs the Turnstile widget. |
| Secrets | `LAILO_JWT_SECRET`, `LAILO_OTP_HASH_SECRET`, DB/Mongo creds | Required env vars; startup fails without them outside `dev`. |
| Multiple services verifying JWTs | `AuthConfig` | Switch HS256 to RS256/ES256 and publish a JWKS endpoint. |
| Client IP behind a load balancer | `ClientInfo` | Trusts the first `X-Forwarded-For` hop – make sure the proxy overwrites that header. |
