-- Where a plan is in its own life: upcoming, happening now, or over.
--
-- Deliberately not a status column. The status of a plan nobody touched is a function of
-- the clock, and a stored column would need a scheduler to keep it true - one that has to
-- have been running, and to have run on time, for the answer to be right. A backend that
-- was asleep for an hour would wake up with a table full of lies.
--
-- These two columns record only what the host actually did: started_at when they started
-- it, ended_at when they ended it. Everything else is derived from them and the schedule
-- on every read - see ActivityStatusResolver (Java) and ActivityStatusSql (the same rule
-- for queries that have to filter on it).
ALTER TABLE activities ADD COLUMN started_at TIMESTAMPTZ;
ALTER TABLE activities ADD COLUMN ended_at   TIMESTAMPTZ;

-- A plan cannot have ended before it began.
ALTER TABLE activities ADD CONSTRAINT chk_activities_lifecycle
    CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at);
