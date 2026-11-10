# Lailo — Project Memory

> Load this at the start of a session (`@memory.md`) to continue development.
> Last updated: 2026-11-10 (media storage added same day). Update the **Status** and **Next steps** sections whenever work lands.

---

## 1. What we are building (the "want")

**Lailo** is an AI-first, **ad-free** e-commerce website.

- After sign-in the user sees **only a chat interface**. They type a prompt; the UI is rendered **dynamically from predefined components** (product grid, comparison table, cart, checkout, order tracker…).
- **Motive:** remove the ads, sponsored listings and clutter that Amazon / Flipkart-style sites are full of. The user focuses only on what they want.
- Target market: India (default country code +91, INR, Indian mobile numbers).

Design direction for the chat / generative UI (proposed, not yet built or confirmed by the owner):
backend turns a prompt into a validated `[{ component, props }]` **UI spec**; `lailo-web` renders it from a **component registry** and ignores anything not in the registry. The model never emits HTML. Never add promotional or sponsored UI.

---

## 2. Owner's standing rules (always follow)

| Rule | Detail |
| --- | --- |
| Enterprise patterns | "Best practices like Amazon / Flipkart." CQRS with command/query buses, transactional outbox → Kafka, Postgres write model, MongoDB read model, Redis for ephemeral state. |
| **Never mock repositories** | Handler/service tests run against embedded databases. Mockito only for true external boundaries (e.g. `OtpSender`, the buses in web slice tests). WireMock for external HTTP. |
| **Pinned embedded test infra** | Backend tests always use embedded **PostgreSQL 18.6.0** (zonky), **MongoDB 7.0.15** (flapdoodle Spring auto-config, set in `application-test.yaml`), embedded **Redis** (codemonstur), embedded **Kafka** (`@EmbeddedKafka`). No Testcontainers, no local services. Don't change versions without asking. |
| Test style requested | JUnit 5 + Mockito, MockMvc-style web tests (→ `WebTestClient`, because the app is WebFlux), WireMock, embedded Postgres. Frontend: Playwright (+ Vitest). |
| Install via Homebrew | If software is missing, install it with `brew`. Docker is installed but its daemon (colima) is normally **stopped** (`colima start` to use it). Tests must never depend on Docker. |
| Compose = infra only | `compose.yaml` runs Postgres, MongoDB, Redis, Kafka. **Never add the frontend or backend to it** (owner's explicit request). |
| Dev OTP | Every OTP is **`123456`** in the dev/test profiles until real SMS/email providers are integrated ("later I will integrate external tools"). |
| **Javadoc on everything** | Every type, constructor and method in `lailo-application` (main **and** test, private and `@Override` included) has Javadoc. Test methods state their condition as a **Given / When / Then** list. Enforced: Checkstyle (`config/checkstyle/javadoc.xml`, Maven `validate`) fails the build on a missing comment; doclint fails it on malformed Javadoc. Avoid raw `<` `>` `&` in Javadoc text; tags (`@param`) go after the list. |
| **JSDoc on everything (web)** | Every named function, component, hook, method, class, type alias and interface in `lailo-web` (app, e2e and unit tests) has JSDoc; every `test()` / `it()` / `it.each()` has a `- Given / - When / - Then` list. Enforced by `eslint-plugin-jsdoc` in `eslint.config.mjs` (`npx eslint .` fails on a gap). Inline callbacks are exempt. Name object parameter types instead of inline `{...}` types. |
| **Builder, not long constructors** | Java types with roughly 4+ fields carry Lombok `@Builder` (records included) and are built with `X.builder()...build()` everywhere, main and test. Keep normalisation/validation in the record's compact constructor so a builder can't bypass it. Short records (1-3 components) and Jackson-bound request DTOs keep plain constructors. Test fixtures expose a prefilled builder (`ProductFixtures.draft(name, category)`). |
| Single `.gitignore` | Only the repo-root `.gitignore` exists (module sections inside it). Don't recreate module `.gitignore` files. |
| Docs | Architecture docs live in `docs/` as **Markdown + PDF**, with Mermaid diagrams. |

---

## 3. Status

| Area | State |
| --- | --- |
| Unified sign-up / sign-in (email **or** mobile, captcha, OTP) | ✅ Done, backend + web, fully tested |
| Sessions (JWT access + rotating refresh with reuse detection), logout | ✅ Done |
| CSRF, rate limiting, enumeration-safe responses, ProblemDetail errors | ✅ Done |
| Outbox → Kafka → Mongo `user_profiles` projection | ✅ Done |
| `/account` page (profile + sign out) | ✅ Done (placeholder until chat exists) |
| Architecture doc (`docs/ARCHITECTURE.md` + `.pdf`) | ✅ Done |
| Product catalogue API (CQRS, same pattern as auth) | ✅ Done, backend only, fully tested |
| Product images + video (SeaweedFS, presigned direct upload) | ✅ Done, backend only, fully tested |
| **Chat screen as post-login home** | ⏳ Next |
| UI spec contract + component registry | ⏳ Planned |
| Cart, checkout, orders | ⏳ Planned |
| Real OTP delivery, Turnstile widget in web | ⏳ Planned (backend Turnstile verification already exists) |

Test counts at last run (2026-11-10): backend **145** (`./mvnw verify`: 68 unit/slice + 77 IT), Vitest **26**, Playwright mocked **26 passed / 2 skipped**, Playwright full-stack **1**. All green.

---

## 4. Repository map

```
Lailo/
├── README.md                 product pitch, status, run & test commands
├── memory.md                 ← this file
├── .gitignore                the ONLY gitignore (sections: repo-wide, Node, lailo-web, lailo-application)
├── compose.yaml              infra only: postgres:18.6-alpine, mongo:7.0.15, redis:8.10.3-alpine, apache/kafka:4.4.0 (KRaft), chrislusf/seaweedfs:4.53 (S3 on :8333)
├── infra/seaweedfs/s3.json   SeaweedFS identities: anonymous Read on lailo-media, lailo/lailo-dev-secret for the app
├── docs/
│   ├── ARCHITECTURE.md/.pdf  architecture write-up (13 Mermaid diagrams)
│   └── tools/                md → PDF renderer (npm install && npm run pdf)
├── .claude/skills/           installed agent skills (see §9); skills-lock.json pins them
├── lailo-application/        Spring Boot 4.1.2, Java 25, WebFlux, Maven wrapper
│   ├── README.md             auth design, API, security controls, prod integration points
│   ├── .env.example
│   └── src/main/java/com/lailo/application/
│       ├── common/cqrs       Command, Query, CommandBus, QueryBus, SimpleCommandBus/QueryBus, HandlerRegistry
│       ├── common/outbox     OutboxMessage, OutboxRepository (FOR UPDATE SKIP LOCKED), OutboxPublisher, OutboxRelay
│       ├── common/storage    ObjectStorage, S3ObjectStorage (AWS SDK v2 -> SeaweedFS), StorageProperties, StorageConfig
│       ├── common/web        ApiException, GlobalExceptionHandler (ProblemDetail + code), ClientInfo
│       ├── features/auth
│       │   ├── api           AuthController, UserController, AuthCookies, dto/
│       │   ├── application   AuthErrors, AuthResult, SessionFactory, command/*, query/*
│       │   ├── domain        User, UserSession, Identifier, IdentifierType, UserStatus, event/AuthEvents
│       │   ├── infrastructure persistence/, redis/, captcha/, otp/, token/, messaging/, readmodel/
│       │   └── config        AuthProperties (validated record), AuthBeansConfig (Clock, SecureRandom, NewTopic)
│       ├── features/product  catalogue, same CQRS layout as auth
│       │   ├── api           ProductController, ProductMediaController, dto/{ProductRequest,MediaUploadRequest}
│       │   ├── application   ProductErrors, command/{Create,Update,Archive,RequestProductMediaUpload,ConfirmProductMediaUpload,DeleteProductMedia}, query/{GetProduct,SearchProducts,ProductView,ProductMediaView,ProductViewMapper,ProductPage,ProductSort}
│       │   ├── domain        Product, ProductDetails, ProductStatus, Slug, ProductMedia, MediaType, MediaStatus, MediaKind, MediaKeys, event/ProductEvents
│       │   ├── infrastructure persistence/{ProductRepository,ProductMediaRepository}, readmodel/{ProductDocument,ProductMediaDocument,ProductCatalogRepository,ProductProjector}, messaging/ProductEventsListener
│       │   └── config        ProductBeansConfig (NewTopic), MediaProperties
│       ├── observability     LogsCommonConfig (owner's AOP logging aspect)
│       └── security/auth/config/AuthConfig.java   filter chain, JWT encoder/decoder, CORS, CSRF
│   └── src/main/resources/
│       ├── application.yaml        prod-safe defaults, secrets from env
│       ├── application-dev.yaml    OTP 123456, insecure cookies, relaxed limits
│       └── db/migration/V1__create_products.sql (sample, dropped by V3), V2__create_auth_tables.sql, V3__create_product_catalogue.sql, V4__create_product_media.sql
│   └── src/test/java/.../
│       ├── support/          IntegrationTest (base; also resets the WireMock object store + givenStoredObject()), EmbeddedInfrastructure (adds objectStorage() WireMock), EmbeddedInfrastructureVersionIT, TestAuthProperties, ProductFixtures
│       ├── features/auth/AuthFlowIT.java          full HTTP flow
│       ├── features/auth/application/**/*IT.java  handler tests on real DBs
│       ├── features/auth/api/AuthControllerTest   WebTestClient slice (buses mocked)
│       ├── features/product/ProductCatalogFlowIT  full HTTP -> outbox -> Kafka -> Mongo -> search
│       ├── features/product/**/*IT.java           handler + projector tests on real DBs
│       ├── features/product/api/ProductControllerTest  WebTestClient slice (buses mocked)
│       ├── features/product/domain/SlugTest       unit
│       ├── support/ProductFixtures                details(...) builders + uniqueCategory()
│       └── .../TurnstileCaptchaServiceTest        WireMock
└── lailo-web/                Next.js 16.4.5, React 19.3, Tailwind 4, TypeScript
    ├── AGENTS.md             Next 16 agent rules (read node_modules/next/dist/docs before coding)
    ├── next.config.ts        /api/* rewrite to BACKEND_URL, security headers, cacheComponents
    ├── proxy.ts              optimistic route guard (/account ↔ /login)
    ├── app/login/            page.tsx (layout) + auth-flow.tsx (client state machine)
    ├── app/account/          page.tsx (server component) + sign-out-button.tsx
    ├── components/auth/      otp-input.tsx, captcha-field.tsx
    ├── lib/                  api/client.ts, api/auth.ts, identifier.ts, safe-redirect.ts, __tests__/
    └── e2e/                  fixtures.ts (API mock + page object), login.spec.ts, fullstack.spec.ts
```

---

## 5. How it works (the "how", condensed)

Full detail with diagrams: `docs/ARCHITECTURE.md`.

**API**

```
GET  /api/v1/auth/csrf          seeds XSRF-TOKEN cookie (204)
GET  /api/v1/auth/captcha       image captcha; answer in Redis captcha:{id}, 2 min, single use
POST /api/v1/auth/otp           {identifier, captchaId, captchaAnswer} → {requestId, maskedIdentifier, expiresInSeconds, resendAfterSeconds}
POST /api/v1/auth/otp/verify    {requestId, otp} → {userId, newUser, accessTokenExpiresAt} + cookies lailo_at, lailo_rt
POST /api/v1/auth/refresh       rotates refresh token (cookie only)
POST /api/v1/auth/logout        revokes session family, clears cookies (204)
GET  /api/v1/users/me           profile (Mongo read model, falls back to Postgres)

GET    /api/v1/products                 public browse/search: q, category, minPrice, maxPrice, inStock,
                                        sort=NEWEST|PRICE_ASC|PRICE_DESC|NAME_ASC, page, size (max 100);
                                        Cache-Control public 60s
GET    /api/v1/products/{productId}     public single product (Mongo, falls back to Postgres)
POST   /api/v1/products                 create (any signed-in caller) -> 201 + Location
PUT    /api/v1/products/{productId}     replace editable details
DELETE /api/v1/products/{productId}     archive (soft delete, idempotent) -> 204

POST   /api/v1/products/{id}/media              {contentType,sizeBytes} -> presigned PUT ticket (no-store)
PUT    {uploadUrl}                              client uploads bytes straight to SeaweedFS
POST   /api/v1/products/{id}/media/{mediaId}    confirm (HEAD-verified) -> publishes to the catalogue
DELETE /api/v1/products/{id}/media/{mediaId}    remove row + event + object -> 204
```

**Key decisions and why**

- **CQRS buses**: controllers only build commands/queries; one handler class per use case. Bus fails fast on duplicate handlers.
- **Identifier normalisation**: email lower-cased; phone to E.164 with default +91 (accepts `98765 43210`, `09876543210`, `+91…`, `0091…`).
- **Send-OTP order**: IP rate limit → captcha (consumed) → resend cooldown + per-identifier limit → store HMAC'd OTP → `OtpSender`. Captcha precedes the identifier quota so bots can't burn a victim's quota. Redis keys use `sha256(identifier)`, never raw PII.
- **OTP**: 6 digits, 5 min TTL, 5 attempts then burned, HMAC-SHA256 with server key bound to requestId, single use via atomic Redis `DEL` (winner proceeds).
- **Verify**: find-or-create user + insert session + outbox events in **one R2DBC transaction**; unique-index race on first sign-up retries as login.
- **Sessions**: access JWT HS256 15 min (sub, sid, roles; no PII); refresh 256-bit opaque, 30 days, only SHA-256 stored, rotated every use; replay of a ROTATED token revokes the whole `family_id` and emits `SessionReuseDetected`.
- **Cookies**: HttpOnly; access `SameSite=Lax; Path=/`; refresh `SameSite=Strict; Path=/api/v1/auth`. Tokens never in JSON bodies.
- **CSRF**: `CookieServerCsrfTokenRepository` + plain (non-XOR) handler + a WebFilter that subscribes to the token so the cookie is always set.
- **Outbox**: `OutboxRelay` polls every 1 s with `FOR UPDATE SKIP LOCKED`, publishes to topic `lailo.auth.user-events.v1` (key = userId, 3 partitions, declared via `NewTopic`), then marks published. `UserEventsListener` → `UserProfileProjector` does idempotent upserts (`$setOnInsert`, `$min`/`$max`).
- **Catalogue (product feature)**: rewritten from the owner's sample controller/service/repository into the auth CQRS shape. Postgres `products` (V3, which drops the `lailo_products` sample table) + outbox -> topic `lailo.catalog.product-events.v1` -> `ProductProjector` -> Mongo `products`. Events (`ProductCreated/Updated/Archived`) carry **full state**; every projection upsert is guarded by `updatedAt < occurredAt` and the duplicate-key error a guarded upsert raises is swallowed as "stale event". Search reads Mongo only (price stored as Decimal128 via `@Field(targetType=DECIMAL128)` so ranges/sorts are numeric, user text quoted with `Pattern.quote`); single-product read falls back to Postgres for read-your-write. Slug = `name-<id prefix>`, unique by construction, never changed by a rename. Archive is a soft delete; editing an archived product is 409 `PRODUCT_ARCHIVED`. Reads public in `AuthConfig`, writes only `authenticated()` — no seller/admin role exists yet (JWT roles claim is hard-coded `CUSTOMER`).
- **Product media**: SeaweedFS (S3 API, `chrislusf/seaweedfs:4.53`) via AWS SDK v2 (`software.amazon.awssdk:s3` 2.57.12), path-style access. **Presigned direct upload**: the API signs a PUT (content type + length are in the signature), the client uploads to the store, then confirms; a `HeadObject` verifies the bytes before the row flips PENDING -> READY and `ProductMediaAdded` is appended. Bytes never pass through Spring. Keys are `products/{productId}/{mediaId}.{ext}` (immutable, CDN-cacheable); only the key is stored, URLs are built at read time from `lailo.storage.public-base-url`. Mongo stores media as a **map keyed by media id** ($set/$unset are idempotent; $push would duplicate on redelivery). Media bucket is anonymously readable (shop-window content). Limits: 10 MB image, 300 MB video, 12 per product. `LAILO_STORAGE_ACCESS_KEY`/`SECRET_KEY` are required env vars. Not built yet: thumbnails/derivatives (belong in a `ProductMediaAdded` consumer), multipart upload, pending-upload sweeper.
- **Captcha strategy**: `BuiltinCaptchaService` (AWT image, default) or `TurnstileCaptchaService` (fails closed), switched by `LAILO_CAPTCHA_PROVIDER`.
- **Frontend BFF**: browser only calls the Next origin; `/api/*` rewritten to Spring so cookies are first-party. `/account` server component calls the API with `Authorization: Bearer <lailo_at>`.
- **API client**: echoes CSRF cookie, maps ProblemDetail → `ApiError(code)`, single-flight refresh on 401 then retry.
- **Design**: palette jamun `#4E2A84` / deep `#351A5E`, ink `#1D1A24`, page `#F6F4F9`, marigold `#F2B33D` (only for the OTP success moment); font **Onest**; split layout on desktop with an oversized "lailo" wordmark; dark mode supported.

---

## 6. Running locally

```bash
brew services start postgresql@17 redis kafka mongodb-community
# DB already created on this machine: role lailo / password lailo, database lailo
cd lailo-application && ./mvnw -B spring-boot:run < /dev/null   # :8080, dev profile auto-enabled
cd lailo-web && npm run dev                                       # :3000
```

SeaweedFS has no Homebrew service here (`brew install seaweedfs` provides `weed`): run `docker compose up -d seaweedfs`, or `weed server -dir=~/.lailo/seaweed -s3 -s3.port=8333 -s3.config=infra/seaweedfs/s3.json`. The dev profile creates the `lailo-media` bucket at startup. Without it, only the media endpoints fail.

Alternative to Homebrew services: `colima start && docker compose up -d --wait` (same ports, so stop the brew services first, or override `POSTGRES_PORT` / `MONGODB_PORT` / `REDIS_PORT` / `KAFKA_PORT` and the matching app env vars). Kafka CLI inside the container must use `--bootstrap-server kafka:29092`. Verified 2026-11-07: full sign-in flow, outbox → Kafka → Mongo projection works against the compose stack.

Open `http://localhost:3000/login`, any email or 10-digit mobile, solve captcha, code **123456**.
To read a captcha answer from a script: `redis-cli GET captcha:<captchaId>`.

## 7. Testing commands

```bash
cd lailo-application && ./mvnw test        # unit + web slice + WireMock + context smoke
cd lailo-application && ./mvnw verify      # + all *IT (embedded Postgres/Mongo/Redis/Kafka)
cd lailo-web && npm test                   # Vitest
cd lailo-web && npm run typecheck && npx eslint .   # includes the JSDoc gate
cd lailo-web && E2E_BASE_URL=http://localhost:3000 npx playwright test          # mocked API, desktop + mobile
cd lailo-web && E2E_FULLSTACK=1 E2E_BASE_URL=http://localhost:3000 npx playwright test fullstack --project=desktop-chromium
cd docs/tools && npm install && npm run pdf  # regenerate docs/ARCHITECTURE.pdf after editing the .md
```

Adding a backend test that touches persistence: extend `support/IntegrationTest`, use `uniqueEmail()`, `uniquePhone()`, `uniqueClient()` for isolation, keep any bean overrides in the base class (one shared Spring context). `otpSender` is a `@MockitoSpyBean` there.

---

## 8. Gotchas learned (read before changing things)

**Environment**
- Machine is slow / memory-tight: first Next compile ~45 s; `mvn dependency:tree` once got OOM-killed. Run long jobs in the background.
- Run `./mvnw spring-boot:run` with `-B` and `< /dev/null` when backgrounded, or it hangs waiting on stdin.
- VS Code's Java extension (Eclipse compiler) builds into `target/classes` too. Some generic reactive code that javac accepts fails in ECJ ("cannot convert from Object to Mono<…>") → add explicit type witnesses (`Mono.<AuthResult>error(...)`, `.<AuthResult>flatMap`).
- `@types/node` must be ≥22 for Vitest's peer range (set to ^24).

**Spring Boot 4 specifics**
- Jackson 3: `tools.jackson.databind.json.JsonMapper` (annotations still `com.fasterxml.jackson.annotation`).
- Starters renamed: `spring-boot-starter-aspectj` (needed by owner's `LogsCommonConfig`), `spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-flyway`. Mongo props are `spring.mongodb.*`.
- Test annotations: `org.springframework.boot.webflux.test.autoconfigure.WebFluxTest`, `org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient`, `@MockitoBean` / `@MockitoSpyBean`.
- Mockito runs as a `-javaagent` (surefire/failsafe argLine via `maven-dependency-plugin:properties`).
- flapdoodle spring4x rewrites `spring.mongodb.uri` to the embedded server, so the prod `uri` in `application.yaml` is safe.

**Object storage / media**
- `StorageProperties` requires non-blank credentials, so a context without `lailo.storage.access-key` fails to start (that is how the first test run broke). Test profile sets dummy credentials; `IntegrationTest` points the endpoint at WireMock.
- A `CommandHandler` must *signal* errors, not throw: validating before the reactive chain made `StepVerifier` throw instead of seeing an error. Wrap eager validation in `Mono.defer`.
- WireMock's HEAD response may not carry a usable `Content-Length`; `ConfirmProductMediaUploadHandler` falls back to the declared size (which the signature already bound) so `size_bytes > 0` holds.
- Once a Mongo catalogue document exists it serves the product read, so a just-confirmed image is not in `GET /products/{id}` until the media event is projected (~1s). The confirm response returns the item itself.

**Reactive / transactions**
- An error inside `.as(tx::transactional)` rolls back everything in it. Commit side effects (e.g. revocations) in their own transaction *before* emitting the error. (Real bug found by switching from mocks to real DBs.)
- Wrap chained steps in `Mono.defer` when order matters so later steps aren't invoked at assembly time.
- Logging aspect prints args at DEBUG: commands/DTOs override `toString()` to mask OTPs, tokens and identifiers. Keep doing that for sensitive types.

**Next.js 16 specifics** (read `lailo-web/node_modules/next/dist/docs/` first)
- `middleware` is now **`proxy.ts`**.
- `cacheComponents: true` → visited pages are kept alive (hidden) in React `<Activity>`; client state survives navigation. Logout uses `window.location.replace` (full reload); the login form resets itself on hide after a successful sign-in.
- Reading `cookies()` must be inside `<Suspense>`.
- React StrictMode double-runs mount effects in dev: guard one-shot effects with a ref (avoids double captcha fetch).
- OTP cells: keep a ref of the latest value; fast typing outruns re-renders.
- Countdowns: use a deadline + interval (background tabs throttle timers; also needed for Playwright's fake clock).
- The refresh cookie is path-scoped to `/api/v1/auth`, so `proxy.ts` can't see it; `/login?next=` attempts a silent refresh.

**Javadoc tooling**
- `./mvnw -B checkstyle:check@javadoc-required` lists missing comments with file:line. Fields are exempt by design.
- `javac -Xdoclint:all,-missing/private` does *not* disable missing-comment warnings for all access levels; the pom lists groups explicitly (`accessibility,html,reference,syntax/private`).

**Playwright**
- API mock fixture is `auto: true`, so every spec is mocked unless it's `fullstack.spec.ts`.
- `getByRole('alert')` also matches Next's route announcer; filter by text.

**Git**
- `.gitignore` directory patterns must be anchored (`/logs/`), since an unanchored `logs/` once matched the Java package `observability/logs/`.
- Nothing has been committed by Claude. Initial commit `77f11a3` is the owner's. Commit only when asked; branch first if on `main`.

**Security housekeeping**
- A MongoDB Atlas password was hard-coded in the original `application.yaml`. It was replaced with env vars and never reached git history; the owner was advised to rotate it.

---

## 9. Installed agent skills (`.claude/skills`)

`frontend-design` (anthropics), `vercel-react-best-practices` (vercel-labs), `java-springboot` + `java-junit` (github/awesome-copilot), `playwright-best-practices` (currents-dev). Use them for matching work.

---

## 10. Next steps (suggested order)

1. **Chat screen** as the post-login home (replace the `/account` redirect target; keep `/account` reachable).
2. **UI spec contract**: `[{component, props}]` schema shared by backend and web; a `chat` feature package with a `SendMessageCommand` handler.
3. **Component registry** in `lailo-web` (start: `ProductGrid`, `ProductCard`, `ComparisonTable`); unknown components ignored; each component tested.
4. **Catalogue / search** behind the chat: the API exists (`/api/v1/products`, media included); still needed are a seller/admin role for writes, Mongo indexes + a real text index, seed data with real images, and a `ProductMediaAdded` consumer for thumbnails / video posters.
5. Cart, addresses, checkout as conversational steps; then orders + tracking.
6. Real `OtpSender` (SMS/email), Turnstile widget in `CaptchaField`, RS256 + JWKS if other services verify tokens.
7. Replace the default `lailo-web/README.md` (still create-next-app boilerplate).

Open decisions to ask the owner about: which LLM/provider powers the chat; product data source; whether `/account` remains a page or becomes a chat component.
