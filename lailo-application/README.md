# lailo-application

Reactive Spring Boot 4 (WebFlux) backend for Lailo.

## Run locally

Services are installed with Homebrew and run as background services:

```bash
brew services start postgresql@17 redis kafka mongodb-community
# one-time database setup (already done on this machine)
psql -d postgres -c "CREATE ROLE lailo LOGIN PASSWORD 'lailo';" && createdb -O lailo lailo

# object store for product media (S3 API on :8333). Either the compose service:
docker compose up -d seaweedfs
# ...or a local install: brew install seaweedfs, then
weed server -dir="$HOME/.lailo/seaweed" -s3 -s3.port=8333 -s3.config=../infra/seaweedfs/s3.json

./mvnw spring-boot:run          # activates the `dev` profile -> OTP is always 123456
```

The `dev` profile uses the credentials from `infra/seaweedfs/s3.json` and creates the `lailo-media` bucket on
startup. Media endpoints answer 500 while no object store is reachable; everything else works without it.

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

## Documentation rule

Every type, constructor and method (main and test code, every access level) carries Javadoc; test methods describe the condition they verify as **Given / When / Then**. Checkstyle ([`config/checkstyle/javadoc.xml`](config/checkstyle/javadoc.xml)) enforces this in the `validate` phase, so any `./mvnw` build fails on a missing comment, and javac's doclint fails the build on malformed Javadoc.

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

## Product catalogue design

The catalogue follows the same CQRS shape as auth: controller → bus → one handler per use case, Postgres
write model, transactional outbox → Kafka → MongoDB read model.

```
GET    /api/v1/products                 -> browse / search (public, cacheable 60 s)
                                           ?q= &category= &minPrice= &maxPrice= &inStock=
                                           &sort=NEWEST|PRICE_ASC|PRICE_DESC|NAME_ASC &page= &size=
GET    /api/v1/products/{productId}     -> one product (public)
POST   /api/v1/products                 -> create (requires a signed-in caller) -> 201 + Location
PUT    /api/v1/products/{productId}     -> replace the editable details
DELETE /api/v1/products/{productId}     -> archive (soft delete, idempotent) -> 204
```

- **Write side** – PostgreSQL `products` via R2DBC (`V3__create_product_catalogue.sql`). Each handler saves the
  aggregate *and* appends `ProductCreated` / `ProductUpdated` / `ProductArchived` to `outbox_messages` in one
  transaction. Events carry the full product state, so a consumer never needs the previous event.
- **Relay** – the shared `OutboxRelay` publishes to Kafka topic `lailo.catalog.product-events.v1`, keyed by
  product id so one product's events stay ordered.
- **Read side** – `ProductEventsListener` → `ProductProjector` upserts MongoDB `products`. Every write is
  conditional on `updatedAt < occurredAt`, so a replayed or out-of-order event can't resurrect stale data (the
  duplicate-key error an upsert raises in that case *is* the "stale event" signal and is swallowed).
  `SearchProductsHandler` queries only Mongo; `GetProductHandler` falls back to Postgres so a caller can read
  its own write during the ~1 s projection lag.
- **Domain rules** – price is INR, scaled to 2 decimals; the slug is `name-<id prefix>`, unique by construction
  and never changed by a rename, so links survive; archiving keeps the row (orders reference it) and only
  removes it from the catalogue; editing an archived product is a 409 `PRODUCT_ARCHIVED`.
- **Authorisation** – reads are public (the catalogue is the shop window). Writes currently need any valid
  access token; when seller / catalogue-admin roles exist they belong behind `hasAuthority` in `AuthConfig`.

## Product media (images and video)

Bytes never pass through this service. The client asks for permission, uploads straight to the S3-compatible
object store with a presigned URL, and then asks the API to publish what it uploaded:

```
1. POST   /api/v1/products/{id}/media            {contentType, sizeBytes}
          -> {mediaId, type, uploadUrl, httpMethod, headers, expiresAt, confirmUrl}   (no-store)
2. PUT    {uploadUrl}                            the client sends the bytes to SeaweedFS itself
3. POST   /api/v1/products/{id}/media/{mediaId}  verified against the store, then published
   DELETE /api/v1/products/{id}/media/{mediaId}  removes the row, the event and the object -> 204
```

Why this shape:

- **A 300 MB video never touches a request thread or the heap here.** The upload is one hop, client → store,
  and the store does the slow part. Proxying it would also mean buffering it twice.
- **The signature binds content type and length**, so a client that declared a 2 MB WebP cannot upload a 90 MB
  MOV: the store itself rejects the mismatch.
- **Two-phase publish.** The row is created `PENDING` when the URL is signed and only becomes `READY` after a
  `HeadObject` confirms the bytes are really there and are what was reserved. An abandoned upload therefore
  leaves a pending row (swept via `ix_product_media_pending`), never a gallery entry that 404s.
- **Only the object key is stored**, never a URL. `ProductViewMapper` builds the URL from
  `lailo.storage.public-base-url` at read time, so putting a CDN in front is a config change with no
  reprojection. Keys are `products/{productId}/{mediaId}.{ext}` - immutable, so every URL is cacheable
  forever, and one product's media sits under one prefix.
- **Read model.** `ProductMediaAdded` / `ProductMediaRemoved` ride the same outbox and topic as the rest of the
  catalogue. In MongoDB media is a **map keyed by media id**, so projecting an item is an idempotent `$set` and
  removing it an `$unset`; a `$push` into an array would duplicate the item on redelivery.
- **Public reads.** Product media is public like any shop window: `infra/seaweedfs/s3.json` grants the
  anonymous identity `Read` on the media bucket only, so a browser or CDN fetches images with no signature.

| Setting | Default | Meaning |
| --- | --- | --- |
| `lailo.storage.endpoint` | `http://localhost:8333` | S3 API of SeaweedFS (or MinIO, AWS S3, R2 - no code change) |
| `lailo.storage.bucket` | `lailo-media` | bucket holding product media |
| `lailo.storage.public-base-url` | `http://localhost:8333/lailo-media` | what browsers fetch from; the CDN host in production |
| `lailo.storage.upload-url-ttl` | `10m` | how long a presigned upload URL lives |
| `lailo.storage.create-bucket-if-missing` | `false` (`true` in dev) | convenience for a fresh local store |
| `lailo.product.media.image.max-bytes` | 10 MB | per-image limit |
| `lailo.product.media.video.max-bytes` | 300 MB | per-video limit |
| `lailo.product.media.max-per-product` | 12 | gallery cap, pending uploads included |

`LAILO_STORAGE_ACCESS_KEY` / `LAILO_STORAGE_SECRET_KEY` are required env vars, like the JWT secret: a
misconfigured deployment fails at startup instead of at the first upload.

In tests the object store is the one dependency that is **stubbed rather than embedded** - it is reached only
over HTTP, so WireMock stands in for it (`EmbeddedInfrastructure.objectStorage()`), exactly as it does for
Cloudflare. Presigning needs no server at all, since it is local signing.

## Going to production – integration points

| Concern | Where | Change |
| --- | --- | --- |
| SMS / email delivery | `OtpSender` | Add a bean (MSG91, Twilio, SES…). `LoggingOtpSender` is only the dev fallback. |
| Real OTPs | `lailo.auth.otp.fixed-code` | Already blank by default; only `application-dev.yaml` sets `123456`. |
| Captcha | `LAILO_CAPTCHA_PROVIDER=turnstile` | `TurnstileCaptchaService` is implemented and WireMock-tested; the web app needs the Turnstile widget. |
| Secrets | `LAILO_JWT_SECRET`, `LAILO_OTP_HASH_SECRET`, DB/Mongo creds | Required env vars; startup fails without them outside `dev`. |
| Multiple services verifying JWTs | `AuthConfig` | Switch HS256 to RS256/ES256 and publish a JWKS endpoint. |
| Client IP behind a load balancer | `ClientInfo` | Trusts the first `X-Forwarded-For` hop – make sure the proxy overwrites that header. |
| Media delivery | `lailo.storage.public-base-url` | Point it at a CDN in front of SeaweedFS; keys are immutable so everything is cacheable forever. |
| Image / video derivatives | new consumer of `lailo.catalog.product-events.v1` | Generate thumbnails, WebP/AVIF variants and video posters off `ProductMediaAdded` instead of on the request path. |
| Very large videos | `ObjectStorage` | Add S3 multipart upload (presign each part) when single-PUT uploads stop being enough. |
| Abandoned uploads | `product_media` | Sweep rows that are still `PENDING` after a while, plus their objects (`ix_product_media_pending`). |
