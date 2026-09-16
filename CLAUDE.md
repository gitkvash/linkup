# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Linkup ("Social Circle Scheduler API") — a Spring Boot backend for creating and discovering
social activities/plans among friends and groups, with geospatial map discovery, a
Redis-backed feed, and push/SSE notifications. Backend only; no frontend in this directory.

This project sits inside a workspace directory (`..`) that also holds its client, `../linkup_app`
(Flutter), and the design handoff the client implements. That workspace has its own `CLAUDE.md`
covering how the two halves are run together; `../linkup_app/CLAUDE.md` covers the client itself.
Neither is a separate checkout — the client's source is readable directly from here, which is what
makes the contract check below possible.

## Commands

```sh
./mvnw -B verify                 # full build: compiles, runs unit tests + Testcontainers integration tests (needs Docker)
./mvnw test                      # unit tests only; container-tagged tests are excluded by default (no Docker required)
./mvnw test -Dtest=ClassName     # run a single test class
./mvnw test -Dtest=ClassName#method   # run a single test method
./mvnw -Dsurefire.excluded.groups= test   # force container-tagged tests to run locally (needs Docker)
docker-compose up -d             # local Postgres+PostGIS (port 5433) and Redis (port 6379)
./mvnw spring-boot:run           # run the app locally (defaults to the `dev` profile)
```

Tests tagged `@Tag("container")` (subclasses of `AbstractIntegrationTest`, e.g.
`FeedFanOutServiceIT`, `ActivityMapRepositoryIT`) spin up real Postgres/PostGIS + Redis via
Testcontainers and require a Docker daemon. They're excluded from `mvn test` via
`surefire.excluded.groups` (see `pom.xml`) so the test phase is meaningful on a machine
without Docker; CI (`.github/workflows/ci.yml`) clears that property and runs `./mvnw verify`.

`VerifyRls.java` (repo root) and the `verify-api*.sh` scripts are standalone manual
verification tools, not part of the Maven build:
- `VerifyRls.java` connects directly to Postgres as the restricted `linkup_app` role
  (bypassing the application entirely) to prove row-level security policies hold at the
  database layer. Run with `java VerifyRls.java` against a migrated database.
- `verify-api*.sh` (`verify-api.sh`, `verify-api-phase3.sh`, `verify-api-phase4.sh`,
  `verify-api-group.sh`, `verify-api-edit.sh`) are curl-based black-box scripts asserting
  security/visibility invariants (e.g. private activities returning 404 for non-owners,
  RLS-driven feed fan-out, creator-only editing) against a live running instance. Run with
  the backend up: `BASE=http://localhost:8080 ./verify-api.sh`.
- `verify-client-contract.sh` asserts something the others don't: that the API matches what
  the Flutter client in `../linkup_app` actually calls — every path in its
  `lib/core/network/*_api.dart`, with the bodies its models emit. A mismatch there is a
  feature that is dead in the app while every test here stays green, which is exactly how
  `PATCH`/`DELETE /activities/{id}` went missing while the app shipped an edit screen.

## Architecture

### Spring Modulith module boundaries

The codebase is organized as Spring Modulith modules, one top-level package each under
`ge.kcamp.linkup`: `identity`, `social`, `activity`, `nlp`, `feed`, `notification`. Module
boundaries are enforced two ways — keep both green when moving code:
- `ModulithVerificationTests` — Spring Modulith's own structural verification
  (`ApplicationModules.of(LinkupApplication.class).verify()`), which fails on cycles and
  illegal cross-module access.
- `ModuleBoundaryArchTests` — an ArchUnit rule on top of it: `feed` and `notification` are
  leaf/consumer modules that react to events from `activity`/`social` and must never be
  depended on by them; `nlp` must not depend on any other module.

Within a module, an `internal` subpackage (e.g. `nlp.internal`, `notification.internal`)
holds implementation classes not meant to be reached from other modules; only
top-level-package classes are the module's public API.

Root package `ge.kcamp.linkup` (no subpackage) holds cross-cutting infrastructure shared by
every module: `DataSourceConfig`/`DatabaseRole` (see below), `UserContext`,
`GlobalExceptionHandler`/`ApiError`, and `LinkupApplication`.

### Request flow / CQRS in `activity`

`activity` splits writes and reads: `ActivityCommandHandler` handles creation (via
`ActivityFactory`, which decides between a `CasualPlanSpec` — no fixed time/place — and a
`StructuredEventSpec`), while `ActivityQueryRepository`/`ActivityQueryService` and
`ActivityMapRepository` serve reads, including the PostGIS `ST_ClusterDBSCAN`-based map
clustering endpoint. `nlp` (`CompositeNlpParserService`, backed by Zoho Hawking plus a
custom `GeorgianTimeExpressionParser`) turns free text like "coffee tomorrow at 5pm" into a
`ParsedActivityText` that feeds the factory — this is what "frictionless" activity creation
means here.

### Events and async work

Cross-module reactions (e.g. activity creation → notification, friendship acceptance →
feed) go through Spring application events, consumed exclusively via
`@ApplicationModuleListener` (never a bare `@TransactionalEventListener(AFTER_COMMIT)`) —
deliberately: it adds `@Async`, `REQUIRES_NEW`, and Modulith's publication log for retries.
That last part isn't just a delivery nicety — `ActivityEventListener`'s Javadoc documents a
prior bug where running the (itself `@Transactional`) dispatcher inline inside an
after-commit callback silently joined an already-committed transaction, so its own writes
were dropped. `spring.modulith.events.republish-outstanding-events-on-restart` is enabled
for at-least-once delivery, which is only safe because notification writes are deduplicated
(see `dedupe_key`, added in `V10`). Every `@ApplicationModuleListener` runs on the single
custom executor defined in `DataSourceConfig` (`applicationTaskExecutor`), not per-listener.

### Row-level security and the dual-role datasource

This is the load-bearing security mechanism and touches most modules. Read
`DataSourceConfig.java` and `DatabaseRole.java` in full before changing anything around
data access, roles, or async execution:

- Two Hikari pools share one `DataSource` bean via `AbstractRoutingDataSource`, keyed by
  the thread-local `DatabaseRole` (`APP` or `SYSTEM`). `APP` (`linkup_app`) is the default
  and is subject to every RLS policy in the schema; `SYSTEM` is the schema owner, exempt
  from RLS, and reserved for background work with no acting user (e.g. notification
  dispatch, feed fan-out reading a creator's friend list).
- The executor backing every `@Async`/`@ApplicationModuleListener` call stamps its threads
  `SYSTEM` via a `TaskDecorator`; nothing calls `DatabaseRole.runAsSystem` directly outside
  that executor and tests.
- `RlsDataSource` stamps every connection borrowed from the app pool with
  `app.current_user_id` (from `UserContext`) via `set_config`, which is what every RLS
  policy reads. A failure to stamp is treated as fatal (connection closed, exception
  propagated) rather than silently serving a connection that matches zero rows — a policy
  matching nothing looks identical to "no data," which was a real, silent bug class here.
  See `V15`–`V18` migrations for the RLS helper functions and per-table policies, and
  `V17__least_privilege_app_role.sql` for creation of `linkup_app` itself.
- Flyway runs migrations under its own separately-configured connection (schema owner),
  never through the routed `DataSource` — required both because migrations need DDL rights
  the app role doesn't have, and because `linkup_app` doesn't exist until `V17` has run.
- Outside the `dev` profile, missing JWT secret (`LINKUP_JWT_SECRET`) or app-role
  credentials (`LINKUP_APP_DB_USERNAME`/`PASSWORD`) fail startup rather than falling back to
  an insecure default — an app silently running as a superuser or with a public JWT key
  would otherwise look completely healthy.

### Feed fan-out

`feed` implements hybrid fan-out: normal users get fan-out-on-write (push into each
friend's Redis timeline, `RedisFeedTimelineRepository`/`FeedTimelineScore`) triggered by
`FeedFanOutEventListener`; users past `linkup.feed.influencer-threshold` fall back to
fan-out-on-read to avoid write amplification. Feed entries expire per
`linkup.feed.ttl-days` and are capped at `linkup.feed.max-timeline-size`.

### Notifications

`NotificationDispatcher` has multiple implementations composed by
`CompositeNotificationDispatcher`: FCM push (`fcm/FcmNotificationDispatcher`), SSE
(`sse/SseNotificationDispatcher`, backing `/api/v1/feed/stream` via `SseEmitterRegistry`,
with a bounded timeout since an open connection pins a servlet thread), and logging. FCM
credentials come from `linkup.fcm.credentials-path`.

### Config profiles

`dev` is the default profile (`spring.profiles.default: dev`) and is the only place
throwaway secrets/passwords live (`application-dev.yaml`). Deployments must set
`SPRING_PROFILES_ACTIVE` to a real environment name so none of that file loads — several
startup checks exist specifically to make a misconfigured deployment fail loudly instead of
running with dev defaults.
