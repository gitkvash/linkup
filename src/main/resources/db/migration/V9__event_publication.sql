-- Spring Modulith's event publication registry (spring-modulith-starter-jpa) is what gives
-- @TransactionalEventListener at-least-once delivery: every publication is written here
-- and marked complete once the listener succeeds. Modulith maps an entity to this table but
-- ships no migration, and Flyway owns the schema here, so it has to be declared explicitly.
--
-- Columns mirror Modulith's own v2 PostgreSQL schema (schema-postgresql.sql in
-- spring-modulith-events-jdbc); keep them in sync when upgrading Modulith.
--
-- Deliberately no row-level security: rows are infrastructure bookkeeping written and read
-- only by the framework, outside of any single user's request context (a retry after restart
-- has no current_user_id at all, and an RLS policy would silently hide pending publications).
CREATE TABLE event_publication
(
    id                     UUID NOT NULL,
    listener_id            TEXT NOT NULL,
    event_type             TEXT NOT NULL,
    serialized_event       TEXT NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
