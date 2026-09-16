# Linkup (Social Circle Scheduler API) Analysis

Based on an analysis of the repository's source code, Maven build lifecycle, Modulith boundaries, and test suite execution, here is a detailed breakdown of the structure, bugs, and potential improvements.

> [!NOTE]
> No files were modified during this analysis. The project compiled cleanly, and all 28 tests (including container-backed integrations and Modulith architecture tests) passed without issue.

## 🏗️ Project Structure

The codebase is built on **Spring Boot 3.x** and **Spring Modulith**, following a clean, loosely-coupled modular monolith architecture. 

The domain is broken into the following boundaries (enforced by ArchUnit):
1. **`identity`**: Manages users, auth, JWT validation, and user search.
2. **`social`**: Manages the social graph, handling `Friendship` (edges) and `Group` interactions.
3. **`activity`**: Handles activity creation (CQRS pattern) dividing operations between `ActivityCommandHandler` and query services/maps (`ST_ClusterDBSCAN` PostGIS logic).
4. **`feed`**: A leaf module for Fan-out-on-write timelines (backed by Redis).
5. **`nlp`**: Domain-agnostic time-expression parser (backed by Zoho Hawking) to parse free text inputs.
6. **`notification`**: A leaf module that dispatches events via FCM (Push) and SSE.

### Security / Infrastructure
* **Dual-role Datasource**: A very sophisticated setup leveraging PostgreSQL Row Level Security (RLS). An `APP` role processes user requests seamlessly applying tenant bounds to the `current_user_id`, while a `SYSTEM` role executes async event listeners natively bypassing RLS.
* **Eventing**: Cross-module work is safely wrapped in Modulith's `@ApplicationModuleListener` ensuring at-least-once delivery semantics to prevent silent transaction-related data drops.

---

## 🐛 Bugs & Potential Bugs

1. **Unpaginated List Endpoints (Memory / N+1 Risk)**
   - *Issue*: A search across the repository shows no usage of Spring Data's `Pageable`. While `Limit.of()` is used in `UserDirectoryService`, other queries (like returning friends lists or activity feeds) might be unbounded. 
   - *Impact*: Users with 10,000+ friends or extensive activity histories will cause Out-Of-Memory (OOM) exceptions and massive database latency.
2. **FCM Credentials Silent Failure**
   - *Issue*: In local testing, `FcmClientProvider` logs `Failed to initialize FCM... push notifications disabled`. If the production environment omits `LINKUP_FCM_CREDENTIALS`, the service continues running silently without delivering push notifications.
   - *Impact*: High risk of missed notifications in production if misconfigured.
3. **SSE Connection Leaks**
   - *Issue*: `SseEmitterRegistry` is keeping open long-lived connections for the feed stream. If clients abruptly disconnect (e.g., closing a mobile app without cleanly terminating the socket), Tomcat servlet threads might hang until the network timeout drops them.
   - *Impact*: Exhaustion of HTTP worker threads on the backend.
4. **PostGIS Clustering Full-Table Scan Risk**
   - *Issue*: `ActivityMapRepository` uses PostGIS `ST_ClusterDBSCAN`. If not carefully constrained by a narrow `ST_Intersects` bounding box beforehand, the clustering algorithm scales non-linearly.
   - *Impact*: Map discovery endpoints could cause severe CPU spikes on PostgreSQL.

---

## 💡 Possible Improvements

1. **Static Analysis Hooks**
   - Incorporate `maven-checkstyle-plugin` or `spotbugs-maven-plugin` directly into the `<build>` phase of `pom.xml`. The project currently relies strictly on ArchUnit which checks boundaries, but not internal code smells.
2. **Fail-Fast for Third-Party APIs**
   - Use `@ConditionalOnProperty` or validate `linkup.fcm.credentials-path` during context load. If the app needs FCM, it should fail to boot rather than logging a warning and functioning brokenly.
3. **GraphQL or Offset Pagination**
   - Standardize all list-based controllers to accept `page` and `size` parameters to prevent malicious sweeping of the Database tables.
4. **Graceful Shutdown configuration**
   - Verify that `server.shutdown=graceful` is set in `application.yaml` alongside a graceful timeout for the `applicationTaskExecutor` ThreadPool. This ensures pending async events (like Feed Fan-out) finish persisting before the JVM halts during Kubernetes/Docker rollouts.
