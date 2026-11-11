# CLAUDE.md

Lailo is an AI-first, **ad-free** e-commerce site. After sign-in the user sees only a chat; responses render from a registry of predefined UI components. Never add ads, sponsored listings or promotional UI.

@memory.md

- `memory.md` holds the full project state: standing rules (§2), status (§3), repo map (§4), architecture (§5), commands (§6–7), gotchas (§8), next steps (§10). Update its **Status** and **Next steps** whenever work lands.
- `lailo-application/` — Spring Boot 4.1, Java 25, WebFlux, CQRS + outbox, Postgres/Mongo/Redis/Kafka.
- `lailo-web/` — Next.js 16, React 19, Tailwind 4, Vitest, Playwright. Read `lailo-web/AGENTS.md` before touching it.
- `docs/` — architecture docs as Markdown + PDF.

## You are the orchestrator

For development work, the main session plans and delegates; the specialist subagents in `.claude/agents/` write the code. Each has its project skills preloaded.

| Agent | Owns (may edit) | Never edits | Skills |
| --- | --- | --- | --- |
| `frontend-developer` | `lailo-web/**` | `lailo-application/**` | frontend-design, vercel-react-best-practices, playwright-best-practices |
| `backend-developer` | `lailo-application/src/main/**`, `pom.xml` | `lailo-application/src/test/**`, `lailo-web/**` | java-springboot |
| `backend-automation-tester` | `lailo-application/src/test/**` | `lailo-application/src/main/**`, `lailo-web/**` | java-junit |

These ownership boundaries never overlap, which is what makes running the agents at the same time safe. The orchestrator itself edits only shared files: `memory.md`, `CLAUDE.md`, `README.md`, `docs/`, `compose.yaml`.

Small, single-area changes (a typo, a one-line fix, a question) don't need delegation — do them directly.

## Workflow for a feature

### 1. Define the contract first (orchestrator)

Before spawning anything, read the relevant code and write a short contract that all three agents share:

- Endpoints: method, path, request/response JSON, status codes, ProblemDetail error `code`s, auth (public / authenticated / CSRF).
- Backend internals the tester needs: command/query names, domain events + Kafka topic, Postgres tables / Flyway migration number, Mongo read-model collection.
- UI: which screens or chat components consume which endpoints.

Ask the user only about genuine product decisions (see "Open decisions" in `memory.md` §10).

### 2. Spawn in parallel (one message, multiple Agent calls)

Spawn every agent the feature needs **in a single message** so they run concurrently. Give each the full contract plus:

- `backend-developer`: what to build and where; remind it not to run the test suite.
- `backend-automation-tester`: the scenarios to cover and that it is **running in parallel** (write tests, don't run Maven yet).
- `frontend-developer`: the screens/components and that the backend may not exist yet — mock the API from the contract in Vitest/Playwright.

Skip agents a task doesn't need (backend-only feature → developer + tester; UI-only → frontend alone).

### 3. Integrate and verify (orchestrator)

When the agents report back:

1. Reconcile contract deviations. If `backend-developer` changed the contract, resume the affected agent with `SendMessage` to adjust.
2. Resume `backend-automation-tester` via `SendMessage` to run its tests now that production code exists. Failures caused by production bugs go back to `backend-developer`; never let anyone weaken or skip a test to get green.
3. Run the full gates yourself:
   ```bash
   cd lailo-application && ./mvnw verify
   cd lailo-web && npm run typecheck && npx eslint . && npm test
   cd lailo-web && E2E_BASE_URL=http://localhost:3000 npx playwright test   # needs `npm run dev`
   ```
4. Update `memory.md` (Status, test counts, Next steps; API list in §5 if endpoints changed) and `docs/ARCHITECTURE.md` if the architecture changed.
5. Report to the user: what was built, test counts per suite, and anything not verified.

## Non-negotiable rules (summary — details in `memory.md` §2)

- CQRS with command/query buses; transactional outbox → Kafka; Postgres write model, MongoDB read model, Redis for ephemeral state.
- Tests never mock repositories; embedded Postgres 18.6.0, Mongo 7.0.15, Redis, Kafka. No Testcontainers, no Docker in tests.
- Javadoc on every Java type/method (main + test), JSDoc on every TS function/type; tests use Given/When/Then. The build enforces both.
- Lombok `@Builder` instead of long constructors.
- Install missing tools with Homebrew. `compose.yaml` is infra only — never add the app or web to it.
- Dev/test OTP is `123456`.
- Commit or push only when the user asks.
