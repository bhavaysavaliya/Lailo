# Lailo

**Shop by asking. Nothing else on the screen.**

Lailo is an AI-first e-commerce website. After you sign in, there is no home page full of banners, no sponsored carousels, no "deals you might like". There is one chat box. You type what you want, and the interface builds itself around your request, using a fixed set of shopping components, then gets out of the way.

> _"Running shoes under ₹4,000, wide fit, for flat feet"_
> → a short grid of matching shoes, filters already applied.
>
> _"Compare the first two"_
> → a side-by-side comparison table.
>
> _"Add the Asics in size 9 and check out"_
> → cart summary, saved address, pay.

## Why

Most large shopping sites are designed to hold your attention, not to save your time. Search results are padded with ads and promoted listings, and every page competes for a click you didn't come to make.

Lailo starts from the opposite assumption: **you already know roughly what you want.** The product's job is to understand the request, show only what answers it, and let you finish.

Principles:

- **No ads, no sponsored placement.** Ranking reflects your request, nothing else.
- **Only what you asked for.** Each reply renders the smallest UI that answers the prompt.
- **Conversation replaces navigation.** No menus or category trees to dig through. Refine by saying so.
- **Predictable, not improvised.** The AI picks from a catalogue of tested components; it never invents markup.

## How it works

```
 you type a prompt
        │
        ▼
┌──────────────────┐   intent + structured     ┌────────────────────────┐
│  Chat (lailo-web)│ ───────────────────────▶  │  Lailo API             │
│                  │                           │  (lailo-application)   │
│  renders a UI    │ ◀───────────────────────  │  understands request,  │
│  spec with       │   UI spec:                │  queries catalogue,    │
│  known components│   [{component, props}]    │  cart, orders          │
└──────────────────┘                           └────────────────────────┘
```

1. The user's message goes to the backend with the conversation context.
2. The backend works out the intent (search, compare, add to cart, track an order…), fetches real data, and replies with a **UI spec**: an ordered list of `{ component, props }` entries.
3. The web app validates that spec against a **component registry** and renders it. Anything not in the registry is ignored, so a model mistake can never produce arbitrary HTML or a broken page.

Components planned for the registry include `ProductGrid`, `ProductCard`, `ComparisonTable`, `FilterChips`, `CartSummary`, `AddressPicker`, `CheckoutSummary` and `OrderTracker`. Each one is designed, accessible and tested on its own, which keeps the experience consistent however the request is phrased.

## Status

Lailo is early. What exists today:

| Area | State |
| --- | --- |
| Sign up / sign in | **Done.** One page, email or mobile number, captcha, OTP. New accounts are created on first sign-in. |
| Sessions & security | **Done.** HttpOnly cookies, short-lived access tokens, rotating refresh tokens with reuse detection, CSRF protection, rate limiting. |
| Chat interface | Planned: the screen users land on after sign-in. |
| UI spec + component registry | Planned. |
| Product catalogue, cart, checkout, orders | Planned. |
| OTP delivery (SMS / email) | Planned. In development every code is `123456`. |

## Repository layout

| Path | What it is |
| --- | --- |
| [`lailo-application/`](lailo-application) | Backend API: Spring Boot 4 (WebFlux, Java 25). PostgreSQL, MongoDB, Redis, Kafka. See its [README](lailo-application/README.md) for the auth design and API. |
| [`lailo-web/`](lailo-web) | Web app: Next.js 16, React 19, Tailwind CSS 4. Calls the API through same-origin `/api` rewrites. |
| `.claude/skills/`, `skills-lock.json` | Agent skills used while developing (frontend design, React, Spring Boot, JUnit, Playwright). |

### Architecture in brief

- **CQRS.** Each use case is a command or query handler. Writes go to PostgreSQL; read models live in MongoDB.
- **Transactional outbox → Kafka.** State changes and their events commit together, then events are published to Kafka to update read models and, later, recommendations and notifications.
- **Redis** for short-lived state: captchas, OTPs, rate limits.
- **Backend-for-frontend.** The browser only talks to the Next.js origin, so auth cookies stay first-party.

## Running locally

Prerequisites (macOS, via Homebrew): Java 25, Maven, Node.js, and the four services.

```bash
brew install postgresql@17 redis kafka mongodb-community
brew services start postgresql@17 redis kafka mongodb-community

# one-time database setup
psql -d postgres -c "CREATE ROLE lailo LOGIN PASSWORD 'lailo';"
createdb -O lailo lailo
```

Start the API (port 8080; the `dev` profile is enabled automatically):

```bash
cd lailo-application
./mvnw spring-boot:run
```

Start the web app (port 3000):

```bash
cd lailo-web
npm install
npm run dev
```

Open <http://localhost:3000/login>, enter any email or 10-digit mobile number, solve the captcha, and use **123456** as the code.

Configuration and secrets are read from environment variables. See [`lailo-application/.env.example`](lailo-application/.env.example) and [`lailo-web/.env.example`](lailo-web/.env.example).

## Tests

| Command | Runs |
| --- | --- |
| `cd lailo-application && ./mvnw test` | Fast backend tests: domain logic, web layer, WireMock contracts |
| `cd lailo-application && ./mvnw verify` | Plus integration tests against embedded PostgreSQL 18.6, MongoDB 7.0.15, Redis and Kafka (no Docker needed) |
| `cd lailo-web && npm test` | Frontend unit tests (Vitest) |
| `cd lailo-web && npm run test:e2e` | Browser tests (Playwright, desktop and mobile) with a mocked API |
| `cd lailo-web && E2E_FULLSTACK=1 npm run test:e2e -- fullstack` | One browser test through the real stack (API and services must be running) |

Backend tests never mock repositories. Handlers run against the embedded databases, and only external systems (OTP delivery, third-party HTTP) are replaced in tests.

## Roadmap

1. Chat screen as the post-login home.
2. UI spec contract and the first registry components (`ProductGrid`, `ProductCard`, `ComparisonTable`).
3. Product catalogue and search behind the chat.
4. Cart, addresses and checkout as conversational steps.
5. Order tracking and history.
6. Real OTP delivery (SMS / email) and a production captcha.
