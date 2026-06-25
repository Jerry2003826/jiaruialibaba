# AGENTS.md

## Cursor Cloud specific instructions

This repo is a single Spring Boot backend service: **`agent-backend-demo`** (Spring AI Alibaba
agent platform demo, Java 21 / Maven / Spring Boot 3.5.7). It also serves a static HTML/JS
"AI Agent Workbench" at `/` — there is **no separate frontend build** (no Node/npm).
Build/run/test commands live in `README.md`; only the non-obvious caveats are captured here.

### Toolchain
- Use the Maven wrapper `./mvnw` (Maven 3.9.6). Java 21 is installed; set
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` (the wrapper warns but still works without it).
- Dependencies are refreshed automatically on VM startup by the update script
  (`./mvnw -B -q dependency:go-offline -DskipTests`). The `~/.m2` cache persists in the snapshot.

### PostgreSQL (required for the running app, NOT for tests)
- PostgreSQL 16 is installed in the snapshot but the service does **not** auto-start.
  Start it each session: `sudo pg_ctlcluster 16 main start`.
- A role+database matching `application-postgres.yml` defaults already exist in the snapshot:
  db `agent_demo`, user `agent_demo`, password `agent_demo`, on `localhost:5432`.
  Recreate only if missing (`sudo -u postgres createuser/createdb`).

### Running the app (dev mode) — important gotchas
- `./mvnw spring-boot:run` with **no profile** falls back to the `default` profile, which uses
  H2 with `ddl-auto: validate` and **fails at startup** (`Schema-validation: missing table ...`).
  You must activate the dev profile: `SPRING_PROFILES_ACTIVE=dev` (or
  `-Dspring-boot.run.profiles=dev`).
- The `dev` profile group expands to `dev,alibaba-strict,postgres,workflow-graph`. The `postgres`
  profile sets `ddl-auto: validate`, so on a **fresh/empty** database the app fails validation.
  For first run / schema bootstrap, override `SPRING_JPA_HIBERNATE_DDL_AUTO=update` (this is what
  `application-dev.yml` intends; Hibernate then creates the tables).
- **Strict mode** (`alibaba-strict`, on by default in dev) requires real DashScope **and**
  DashVector credentials, otherwise AI/RAG paths throw. To run locally without those external
  Alibaba secrets, disable it:
  `DEMO_ALIBABA_STRICT_MODE=false DEMO_AI_FALLBACK_ENABLED=true DEMO_RAG_KEYWORD_FALLBACK_ENABLED=true DEMO_RAG_RETRIEVER=keyword`.
  Chat/RAG then return fallback responses; local tool-calling and the graph workflow runtime
  still execute for real.

### API security (affects manual testing and the workbench UI)
- `DEMO_SECURITY_JWT_SECRET` (>= 32 bytes) is **required at boot** and is NOT in `.env.example`.
- Every `/api/**` endpoint except `GET /api/health` requires an HS256 Bearer JWT signed with that
  secret, carrying `SCOPE_*` authorities (`chat.execute`, `agent.execute`, `rag.read`/`rag.write`/
  `rag.query`, `workflow.read`/`workflow.edit`/`workflow.run`/`workflow.publish`, `trace.read`).
  Mint one with PyJWT: `jwt.encode({"scope": "<space-separated scopes>", "exp": ...}, secret, "HS256")`.
- The static workbench (`src/main/resources/static/app.js`) does **not** send an `Authorization`
  header, so UI actions beyond the health/runtime panel return 401. To exercise the UI end-to-end,
  put a header-injecting reverse proxy in front of `:8080` (or otherwise inject the Bearer token)
  and point the browser at the proxy.

### Tests / lint
- `./mvnw test` runs the full suite (250 tests). Tests are self-contained: `src/test/resources/
  application.properties` forces H2 in-memory, strict mode off, fallback on, and a fixed test JWT
  secret — **no Postgres and no Alibaba keys needed**.
- There is no dedicated linter/formatter plugin (no Checkstyle/Spotless); the closest "lint" is
  `./mvnw -q -DskipTests compile` (compilation check).
