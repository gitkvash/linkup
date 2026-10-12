-- "Why can't you come?": someone declining an invitation may leave the host a short reason.
--
-- A column on participants rather than a table of its own: there is at most one answer per
-- person per plan, and the reason is part of that answer - it is replaced when they answer
-- again and cleared when they say yes (see ParticipantRepository.upsertStatus).
--
-- 200 characters, the same limit as the message on a time suggestion (V39): two or three
-- sentences, about 30-40 words. Nullable, because saying why is optional. No new grant is
-- needed: the application role already has INSERT/UPDATE on participants (V17), and the RLS
-- policies are about rows, not columns. Who may *read* the reason is decided in
-- ActivityParticipationService.listParticipants (host and the decliner only), not here, because
-- fellow guests can legitimately see the participants row.

ALTER TABLE participants ADD COLUMN decline_reason VARCHAR(200);
