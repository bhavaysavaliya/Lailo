# Lailo — Project Memory

> Load this at the start of a session (`@memory.md`) to continue development.
> Last updated: 2026-11-11 (cart, wishlist, orders, payments, saga, shared notifications). Update the **Status** and **Next steps** sections whenever work lands.

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
| Cart + wishlist (CQRS, outbox, live catalogue pricing) | ✅ Done, backend only, fully tested |
| Orders: checkout, **shipped / to-be-shipped** tabs, fulfilment pipeline | ✅ Done, backend only, fully tested |
| Payments (gateway port, simulated adapter, signed webhook, refunds) | ✅ Done, backend only, fully tested |
| Checkout **saga** with compensation (stock + refund) | ✅ Done, backend only, fully tested |
| Shared **EmailSender / MessageSender** ports + idempotent dispatcher; auth OTPs now ride them | ✅ Done |
| Order-placed email + SMS (own consumer group) | ✅ Done |
| **Chat screen as post-login home** | ⏳ Next |
| UI spec contract + component registry | ⏳ Planned |
| Real OTP delivery, Turnstile widget in web | ⏳ Planned (backend Turnstile verification already exists) |

Test counts at last run (2026-11-11): backend **247** (`./mvnw verify`: 107 unit/slice + 140 IT), Vitest **26**, Playwright mocked **26 passed / 2 skipped**, Playwright full-stack **1**. All green.

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
│       │                     (buses resolve handlers LAZILY via ObjectProvider - handlers depend on the bus)
│       ├── common/outbox     OutboxMessage, OutboxRepository (FOR UPDATE SKIP LOCKED), OutboxPublisher, OutboxRelay
│       ├── common/notification EmailSender, MessageSender, EmailMessage, TextMessage, Recipient,
│       │                     NotificationRequest, NotificationDispatcher (dedupe claim), NotificationDelivery,
│       │                     LoggingEmail/MessageSender, NotificationProperties, NotificationConfig
│       ├── common/saga       SagaInstance, SagaRepository, SagaStore (compare-and-set step), SagaStatus
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
│       ├── features/cart     CartItem (keyed by user, no carts table), CartReader, Add/Update/Remove/Clear,
│       │                     GetCartQuery, CartController, CartEvents -> lailo.cart.cart-events.v1
│       ├── features/wishlist WishlistItem, WishlistReader, Add/Remove/MoveToCart, WishlistController,
│       │                     WishlistEvents -> lailo.wishlist.wishlist-events.v1
│       ├── features/order    Order (@Embedded ShippingAddress), OrderItem, OrderStatus, OrderBucket,
│       │                     OrderNumber, OrderTotals, CancelReason; PlaceOrder/ConfirmOrder/CancelOrder/
│       │                     AttachPaymentToOrder/AdvanceOrderFulfilment; GetOrder/ListOrders (+OrderCounts);
│       │                     readmodel OrderDocument/OrderProjector (projects order AND payment events);
│       │                     saga/CheckoutSaga; messaging/{OrderEventsListener,CheckoutSagaListener};
│       │                     notification/{OrderNotificationTemplates,OrderNotificationListener};
│       │                     config/CheckoutProperties; OrderEvents -> lailo.order.order-events.v1
│       ├── features/payment  Payment, PaymentMethod, PaymentStatus; gateway/{PaymentGateway,
│       │                     SimulatedPaymentGateway, GatewayIntent/Request/Outcome}; Initiate/Confirm/
│       │                     Refund/HandlePaymentWebhook; GetPayment; PaymentController;
│       │                     PaymentEvents -> lailo.payment.payment-events.v1 (keyed by ORDER id)
│       ├── observability     LogsCommonConfig (owner's AOP logging aspect)
│       └── security/auth/config/AuthConfig.java   filter chain, JWT encoder/decoder, CORS, CSRF
│   └── src/main/resources/
│       ├── application.yaml        prod-safe defaults, secrets from env
│       ├── application-dev.yaml    OTP 123456, insecure cookies, relaxed limits
│       └── db/migration/V1 (sample, dropped by V3), V2__create_auth_tables.sql, V3__create_product_catalogue.sql,
│           V4__create_product_media.sql, V5__create_cart_and_wishlist.sql, V6__create_orders.sql,
│           V7__create_payments.sql, V8__create_saga_and_notifications.sql
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

GET    /api/v1/cart                             priced live from the catalogue; no-store
POST   /api/v1/cart/items                       {productId, quantity} (repeat add raises the line)
PUT    /api/v1/cart/items/{productId}           {quantity} (1..20; 0 is rejected, not a delete)
DELETE /api/v1/cart/items/{productId}           -> updated cart, or 404 CART_ITEM_NOT_FOUND
DELETE /api/v1/cart                             empty, idempotent -> 204

GET    /api/v1/wishlist                         saved products + live stock/availability
POST   /api/v1/wishlist/items                   {productId, note?} idempotent
DELETE /api/v1/wishlist/items/{productId}
POST   /api/v1/wishlist/items/{productId}/move-to-cart   returns {cart, wishlist}

POST   /api/v1/orders                           {address, paymentMethod} -> 201 + Location
GET    /api/v1/orders?bucket=TO_BE_SHIPPED|SHIPPED|CANCELLED|ALL&page&size   + counts for every tab
GET    /api/v1/orders/{orderId}                 Mongo, falls back to Postgres (read-your-write)
POST   /api/v1/orders/{orderId}/cancel          -> 204; stock + refund follow via the saga
POST   /api/v1/orders/{orderId}/fulfilment      {target: PACKED|SHIPPED|DELIVERED, courierName?, trackingNumber?,
                                                 expectedDeliveryDate?} -> 204  (needs an ops role eventually)

GET    /api/v1/payments/{paymentId}
POST   /api/v1/payments/{paymentId}/confirm     outcome read from the gateway, never from the client
POST   /api/v1/payments/webhook                 PUBLIC + CSRF-exempt; X-Lailo-Signature HMAC, fails closed
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
- **Cart / wishlist**: keyed by **user id, no parent table**; unique `(user_id, product_id)` makes a repeat add an
  update. Read from the write model + one bulk catalogue read (`GetProductsQuery`), NOT a Mongo projection: a cart is
  read right after every write (projection lag would show) and the only denormalisable fields - name, price, stock -
  are exactly the ones that must be live at checkout. Live price is charged; `unitPriceWhenAdded` is shown as "was".
  An archived product stays in the cart marked unavailable (and blocks checkout) rather than silently vanishing.
- **Orders**: Postgres `orders` (+ `@Embedded.Nullable(prefix="ship_")` ShippingAddress) and `order_items`
  (immutable snapshot: no FK to products). Mongo `orders` read model stores a derived `bucket`, so a tab is one
  equality match; the list does NOT fall back to Postgres (a list must stay one indexed query) while the single-order
  read does. `OrderBucket` owns shipped/to-be-shipped in one place. `OrderNumber` = `LAILO-yyyyMMdd-XXXXXX` from an
  unambiguous alphabet, unique index + one retry on collision.
- **Checkout saga** (`common/saga` + `CheckoutSaga`): `PlaceOrderHandler` commits only order + lines + cart clear +
  `OrderPlaced` in one tx; the saga then reserves stock, opens the payment, confirms on authorisation, and on
  cancellation releases stock + refunds. Idempotency is a compare-and-set on `saga_instances.step`, not the consumer
  offset. Compensation is claimed **one-shot** (`RELEASING`) because a double release would invent stock; a genuine
  compensation failure becomes `COMPENSATION_FAILED` for an operator instead of retrying forever.
- **Stock**: `ReserveStockCommand` is `UPDATE ... WHERE stock_quantity >= :qty` - the DB prevents overselling, not
  the app; all lines in one tx so a partially reserved order can't exist. Emits `ProductStockChanged` (absolute
  quantity, never a delta, so a replay can't double-count).
- **Payments**: `PaymentGateway` port + `SimulatedPaymentGateway` (reference containing `decline` is its documented
  test hook; HMAC-signs its own webhooks). The gateway is called BEFORE the tx opens - never hold a DB tx across a
  third-party call. A decline is committed in its own tx *before* the error is signalled, or the saga would never
  learn to cancel. Confirming an authorised payment is a no-op (client and webhook both call it). Webhook ignores the
  body's outcome and re-asks the provider, so a forged body cannot mark an order paid. COD is authorised on creation.
- **Notifications**: `common/notification` holds the shared `EmailSender`/`MessageSender` ports used by EVERY feature -
  auth OTPs now go out through them (`NotificationOtpSender` replaced `LoggingOtpSender`). `NotificationDispatcher`
  dedupes per `(dedupe_key, channel)` claim row, so at-least-once Kafka delivery becomes exactly-once-per-channel;
  a FAILED row is re-claimable so the consumer's retry resends. Order-placed email+SMS is its own consumer group, so
  a provider outage never stalls the projector or the saga. OTP bypasses the dispatcher (every resend is a new code).
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

**Outbox / Kafka / consumers (learned the hard way 2026-11-11)**
- `OutboxRelay` must **drain the locked rows (`collectList`) before publishing**. Publishing straight off the
  `FOR UPDATE SKIP LOCKED` cursor leaves that result stream open while `markPublished` runs on the same
  connection, and one R2DBC connection cannot interleave two statements: the connection sits
  *idle in transaction* mid-cursor and the batch never finishes. Symptom was brutal to diagnose - the relay
  silently stopped, so every projection test (auth, catalogue, checkout) timed out while all handler tests
  passed. Diagnosis: `jstack` showed no thread in the chain, and `pg_stat_activity` showed
  `idle in transaction / ClientRead` right after the SELECT.
- `KafkaTemplate.send()` **blocks** the caller while the producer fetches topic metadata (`max.block.ms`,
  default 60s). Never call it on an event-loop thread - the relay now does
  `.subscribeOn(Schedulers.boundedElastic())`, and `max.block.ms` is pinned to 5s so a broker hiccup fails
  instead of hanging.
- **One Spring context for all ITs.** `@AutoConfigureWebTestClient` lives on `IntegrationTest`, not on
  subclasses: a second cached context starts a second set of consumers in the SAME groups, and every switch
  between contexts rebalances all of them (one gap measured at 23s - long enough to fail an unrelated test).
- A test that boots embedded infrastructure is an **`*IT`**, never a `*Test`. `LailoApplicationTests` was
  renamed `LailoApplicationIT` so the build does not start Postgres/Mongo/Kafka twice.
- `application-test.yaml` pins every `lailo.kafka.*.partitions` to **1**, matching what `@EmbeddedKafka`
  pre-creates; letting the admin client grow topics to 3 partitions on a live embedded broker is flaky.
- Two events about the same aggregate must not share an `occurredAt`: Mongo stores millisecond precision, so
  the second one looks like a replay and the `updatedAt < occurredAt` guard drops it. COD hit this exactly
  (`PaymentInitiated` + `PaymentAuthorized` in one tx) - the authorisation is now stamped +1ms.

**CQRS buses**
- Handlers that compose other use cases (checkout -> clear cart, saga -> reserve stock, cart -> read catalogue)
  depend on the bus, so `SimpleCommandBus`/`SimpleQueryBus` resolve handlers **lazily** through
  `ObjectProvider` and build their index in `afterSingletonsInstantiated()`. A constructor `List<Handler>`
  injection is a circular dependency and the context will not start.

**Reactive / transactions**
- An error inside `.as(tx::transactional)` rolls back everything in it. Commit side effects (e.g. revocations) in their own transaction *before* emitting the error. (Real bug found by switching from mocks to real DBs.)
- Wrap chained steps in `Mono.defer` when order matters so later steps aren't invoked at assembly time.
  Real bug found this way: `validate(cart).then(write(cmd, cart))` priced an **empty** cart at assembly time, so
  checkout threw `IllegalArgumentException` instead of `CART_EMPTY`. Needs `.then(Mono.defer(() -> write(...)))`.
- `Mono<Void>` completes **empty**, so `x.flatMap(...).switchIfEmpty(insert)` runs the insert branch as well.
  Real bug: the wishlist's "update the note" path fell through to the insert and hit the unique index. Use
  `.map(Optional::of).defaultIfEmpty(Optional.empty())` instead.
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
5. Cart / checkout / order tracking as conversational steps - the APIs all exist now (§5); what's missing is the
   web UI, a saved-address book (addresses are entered per order today), and a real courier integration behind
   `AdvanceOrderFulfilmentCommand`.
6. Real `OtpSender` (SMS/email), Turnstile widget in `CaptchaField`, RS256 + JWKS if other services verify tokens.
7. Replace the default `lailo-web/README.md` (still create-next-app boilerplate).

8. Seller / ops roles: catalogue writes and `POST /orders/{id}/fulfilment` are only `authenticated()` today (the JWT
   `roles` claim is still hard-coded `CUSTOMER`). The fulfilment route is restricted to the order's owner as a
   placeholder - it needs a real ops role.
9. Mongo indexes for the orders read model: `(userId, bucket, placedAt)`, `(userId, placedAt)`, unique `orderNumber`.
10. Sweepers / monitors: stuck sagas (`ix_saga_instances_unfinished`, `COMPENSATION_FAILED`), failed notifications
   (`ix_notification_deliveries_failed`), abandoned media uploads.

Open decisions to ask the owner about: which LLM/provider powers the chat; product data source; whether `/account` remains a page or becomes a chat component; which payment provider (Razorpay / Cashfree / Stripe) and which SMS + email providers to wire into the existing ports.
