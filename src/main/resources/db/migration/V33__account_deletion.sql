-- Deleting your own account (DELETE /api/v1/users/me), which the App Store requires of any
-- app that lets you create one.
--
-- V14 left every user-referencing key NO ACTION on purpose: deleting a user fans out into
-- their plans, friendships, groups and notifications, and that should be "an explicit,
-- reviewed operation, not a side effect of one DELETE". This is that operation. The keys
-- stay NO ACTION, so a table added later that references users makes deletion fail loudly
-- (AccountDeletionIT) instead of being cascaded - or left behind - without anyone deciding.
--
-- One SECURITY DEFINER function rather than a DELETE per table from Java, for two reasons:
--
-- 1. Several steps touch rows the caller cannot write under RLS. Relabelling another
--    member's plan that was shared with a group the caller owns is an UPDATE on someone
--    else's activity, which activity_update_policy (rightly) refuses.
-- 2. The rows span four modules. One statement from `identity` keeps the whole delete in
--    one transaction without identity reaching into activity, social and notification.
--
-- It takes no argument: the account deleted is always app_current_user(), so the most a
-- caller can do with it is delete themselves. A connection with no user (background work,
-- an anonymous request) gets an error, never a no-op that looks like success.
--
-- What happens to each thing the account touches:
--
--   activities they created   deleted; participants and locations follow (V14 cascade).
--                             Other people's invitations to those plans go with them.
--   groups they own           deleted; group_members follow (V14 cascade). There is no
--                             ownership transfer anywhere in the product, and a group is
--                             its owner's list. Plans other members shared with the group
--                             become PRIVATE - creator plus participants, which is what
--                             V18 did for GROUP rows with no group to point at - rather
--                             than vanishing with it.
--   their memberships,        deleted.
--   participations,
--   friendships (either side
--   or requested by them),
--   notifications, devices
--   refresh_tokens            deleted by V28's ON DELETE CASCADE.
--   users row                 deleted.
--
-- Not touched: notifications *other* people received about this account keep their stored
-- title and body. They have no column naming the actor (V27), and the client already
-- treats a plan or person that answers 404 as gone.

CREATE OR REPLACE FUNCTION app_delete_current_account() RETURNS boolean
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    me uuid := app_current_user();
BEGIN
    IF me IS NULL THEN
        RAISE EXCEPTION 'app_delete_current_account() needs an authenticated caller'
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    DELETE FROM activities WHERE creator_id = me;

    UPDATE activities
       SET visibility = 'PRIVATE', group_id = NULL
     WHERE group_id IN (SELECT g.group_id FROM groups g WHERE g.owner_id = me);
    DELETE FROM groups WHERE owner_id = me;

    DELETE FROM group_members WHERE user_id = me;
    DELETE FROM participants  WHERE user_id = me;
    DELETE FROM friendships
     WHERE user_a_id = me OR user_b_id = me OR requested_by = me;
    DELETE FROM notifications WHERE recipient_user_id = me;
    DELETE FROM device_tokens WHERE user_id = me;

    DELETE FROM users WHERE user_id = me;
    RETURN FOUND;
END
$$;

COMMENT ON FUNCTION app_delete_current_account() IS
    'Deletes the calling account (app_current_user()) and everything that belongs to it. Returns false if there was no such account. See V33.';

-- EXECUTE defaults to PUBLIC for a new function. Only the request role calls it.
REVOKE ALL ON FUNCTION app_delete_current_account() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_delete_current_account() TO linkup_app;
