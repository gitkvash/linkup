# CLAUDE.md

Spring Boot backend for Linkup ("Social Circle Scheduler API"): activities and plans among
friends and groups, PostGIS map discovery, a Redis-backed feed, and push/SSE notifications. The
Flutter client is the sibling `../linkup_app`, readable directly, with its own `CLAUDE.md`.
`../CLAUDE.md` covers running both halves and the repo map
(`python ../.claude/repo_map.py --find X`).

## Commands

```sh
./mvnw -q test                          # unit tests; container-tagged tests excluded (no Docker)
./mvnw -q test -Dtest=ClassName[#method]
./mvnw -B verify                        # everything incl. Testcontainers ITs (needs Docker); what CI runs
./mvnw -Dsurefire.excluded.groups= test # force container tests locally (needs Docker)
docker-compose up -d                    # Postgres+PostGIS on 5433, Redis on 6379
./mvnw spring-boot:run                  # `dev` profile by default
```

`@Tag("container")` tests are subclasses of `AbstractIntegrationTest` (e.g. `FeedFanOutServiceIT`,
`ActivityMapRepositoryIT`). `mvn test` excludes them via `surefire.excluded.groups` in
`pom.xml`. CI (`.github/workflows/ci.yml`) clears that property.

Manual checks, outside the Maven build:

- `java VerifyRls.java` connects to a migrated database as the restricted `linkup_app` role,
  bypassing the app, to prove the RLS policies hold.
- `verify-api*.sh` (`verify-api`, `-phase3`, `-phase4`, `-group`, `-edit`) are curl black-box
  checks of security and visibility invariants: private activities return 404 to non-owners,
  RLS-driven fan-out, creator-only edits. Run them against a live instance:
  `BASE=http://localhost:8080 ./verify-api.sh`.
- `verify-client-contract.sh` asserts that the API serves every path in
  `../linkup_app/lib/core/network/*_api.dart`, with the bodies the app sends. This catches a
  feature that is dead in the app while every test here passes, which is how
  `PATCH`/`DELETE /activities/{id}` once went missing.

## Modules (Spring Modulith)

There's one top-level package per module under `ge.kcamp.linkup`: `identity`, `social`,
`activity`, `feed`, `notification`. Two tests guard the boundaries, and both must stay
green when moving code:

- `ModulithVerificationTests` fails on cycles and illegal cross-module access.
- `ModuleBoundaryArchTests` (ArchUnit) enforces that `feed` and `notification` are leaf
  consumers of `activity`/`social` events, and must never be depended on by them.

`internal` subpackages (e.g. `notification.internal`) are private to their module.
Only top-level classes are a module's API. The root package holds shared infrastructure:
`DataSourceConfig`/`DatabaseRole`, `UserContext`, `GlobalExceptionHandler`/`ApiError`.

## Activity: writes vs reads

- Writes go through `ActivityCommandHandler`. Every new plan is a `StructuredEventSpec` built
  from the form. `CasualPlanSpec` and `ActivityType.CASUAL_PLAN` remain only because plans
  created by the removed free-text endpoint (`POST /activities/from-text`) still carry that type.
- Reads go through `ActivityQueryRepository`/`ActivityQueryService` and `ActivityMapRepository`.
  The map clusters with PostGIS `ST_ClusterDBSCAN`.

## Events

Cross-module reactions (activity created → notification, friendship accepted → feed) use Spring
application events. Consume them **only with `@ApplicationModuleListener`**, never a bare
`@TransactionalEventListener(AFTER_COMMIT)`.

- `@ApplicationModuleListener` adds `@Async`, `REQUIRES_NEW` and Modulith's publication log.
  The notification listeners override it with `propagation = NOT_SUPPORTED`: the row is written in
  its own short transaction (`INSERT … ON CONFLICT (dedupe_key) DO NOTHING`), and FCM/SSE go out
  after it commits, only if the row was new. A failed insert throws, so the publication stays
  incomplete and is retried; FCM/SSE failures are only logged.
- Completed publications are deleted (`completion-mode: delete`). `EventPublicationResubmitter`
  resubmits incomplete ones older than `linkup.events.resubmit-older-than` (5 min) between restarts.
- `ActivityEventListener`'s Javadoc records the bug this prevents: an inline `@Transactional`
  dispatcher in an after-commit callback joined the already-committed transaction and its writes
  were silently dropped.
- `republish-outstanding-events-on-restart` gives at-least-once delivery. That is safe only
  because notification writes dedupe on `dedupe_key` (V10), which includes the event's
  `occurredAt` so a redelivery dedupes but a new request after a decline does not.
- All listeners run on the one `applicationTaskExecutor` in `DataSourceConfig`.

## Row-level security and the dual-role datasource

This is the load-bearing security mechanism. Read `DataSourceConfig.java` and
`DatabaseRole.java` in full before touching data access, roles or async execution.

- Two Hikari pools sit behind one routing `DataSource`, keyed by the thread-local `DatabaseRole`.
  - `APP` (`linkup_app`) is the default and subject to every RLS policy.
  - `SYSTEM` is the schema owner, exempt from RLS, and reserved for background work with no
    acting user, such as notification dispatch and fan-out reading a friend list.
- The executor behind every `@Async`/`@ApplicationModuleListener` stamps its threads `SYSTEM`
  through a `TaskDecorator`. The one call by hand is `DeviceTokenService.claim`, for the single
  statement that takes an FCM token off *other* accounts when a phone switches users; the caller's
  own row is written as `APP`. Don't add more.
- `RlsDataSource` stamps each app-pool connection with `app.current_user_id` (from
  `UserContext`) via `set_config`; every policy reads that value. If stamping fails, the
  connection is closed and the error propagated. Serving the connection anyway would match zero
  rows, which looks just like "no data" and was a real silent bug class.
- The RLS helpers and policies are in V15–V18. `linkup_app` itself is created in
  `V17__least_privilege_app_role.sql`.
- Flyway runs on its own owner connection, never through the routed `DataSource`. It needs DDL
  rights the app role lacks, and `linkup_app` doesn't exist until V17 has run.
- Outside `dev`, startup fails if `LINKUP_JWT_SECRET` or the app-role credentials
  (`LINKUP_APP_DB_USERNAME`/`PASSWORD`) are missing, rather than looking healthy while running as
  a superuser or with a public key.

## Feed, notifications, profiles

- **Feed** is hybrid fan-out.
  - Normal users get fan-out-on-write into each friend's Redis timeline
    (`FeedFanOutEventListener` → `RedisFeedTimelineRepository`, `FeedTimelineScore`).
  - Users above `linkup.feed.influencer-threshold` get fan-out-on-read.
  - `linkup.feed.ttl-days` and `linkup.feed.max-timeline-size` bound the timeline.
- **Notifications**: `CompositeNotificationDispatcher` composes three dispatchers:
  - FCM (`fcm/`), with credentials from `linkup.fcm.credentials-path`
  - SSE (`sse/`, via `SseEmitterRegistry`), which backs `/api/v1/feed/stream`; it has a bounded
    timeout because an open connection pins a servlet thread
  - logging
- **Profiles**: `dev` is the default and the only place throwaway secrets live
  (`application-dev.yaml`). Deployments must set `SPRING_PROFILES_ACTIVE` to a real environment
  so that file never loads. Several startup checks exist to make a misconfigured deployment fail
  loudly.
