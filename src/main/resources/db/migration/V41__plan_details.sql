-- What a plan says beyond its title: a note for the people coming, a meeting point, and a cap
-- on how many can go.
--
-- description    "bring a ball", "we'll order pizza after". 500 characters is a few sentences;
--                a plan is a message to friends, not a listing. Nullable - most plans say nothing.
-- meeting_point  where exactly to find the host once at the place ("by the fountain"). Separate
--                from locations.address_text, which names the place and feeds map search; this
--                is only ever shown, never searched, so it needs no index and no location row.
-- max_participants  the most people who can be JOINED at once, host included. Null = no limit,
--                which is every plan created before this column existed. A cap of 1 would be a
--                plan only its host can be in, so the floor is 2.
--
-- No new grants or policies: the application role already has INSERT/UPDATE on activities (V17)
-- and the RLS policies are about rows, not columns. The cap is enforced in
-- ActivityParticipationService, under a per-plan advisory lock, rather than by a constraint:
-- it is a rule about how many participants rows exist, which a CHECK on this table cannot see.

ALTER TABLE activities
    ADD COLUMN description      VARCHAR(500),
    ADD COLUMN meeting_point    VARCHAR(120),
    ADD COLUMN max_participants INTEGER;

ALTER TABLE activities
    ADD CONSTRAINT chk_activities_max_participants
        CHECK (max_participants IS NULL OR max_participants >= 2);
