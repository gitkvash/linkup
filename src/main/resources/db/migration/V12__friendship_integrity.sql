-- Moves two friendship invariants from Java into the schema.
--
-- Verified before writing this: the table is empty, no rows violate the ordering,
-- there are no reciprocal duplicate pairs, and no row has a null requested_by. The
-- defensive statements below are kept anyway so this is safe to run on an
-- environment whose data hasn't been checked.

-- A friendship is one undirected edge stored with the lower UUID first. That rule
-- lived only in SocialGraphService.canonicalFirst/canonicalSecond, so any other
-- insert path could create both (a,b) and (b,a) and every "are we friends" query
-- would then be answering about half the data.
DELETE FROM friendships f
 WHERE f.user_a_id >= f.user_b_id
   AND EXISTS (
       SELECT 1 FROM friendships mirror
        WHERE mirror.user_a_id = f.user_b_id
          AND mirror.user_b_id = f.user_a_id
   );

UPDATE friendships
   SET user_a_id = user_b_id, user_b_id = user_a_id
 WHERE user_a_id > user_b_id;

DELETE FROM friendships WHERE user_a_id = user_b_id;

ALTER TABLE friendships
    ADD CONSTRAINT chk_friendships_canonical_order CHECK (user_a_id < user_b_id);

-- requested_by decides who is allowed to accept a pending request. V6 added it
-- nullable, and SocialGraphService dereferences it without a null check - so a row
-- predating V6 made accepting throw an NPE, surfacing as a 500 with no explanation.
-- Such rows are unactionable by definition, so remove them rather than guessing an
-- owner: backfilling would silently hand the right to accept to the wrong party.
DELETE FROM friendships WHERE requested_by IS NULL;

ALTER TABLE friendships ALTER COLUMN requested_by SET NOT NULL;
