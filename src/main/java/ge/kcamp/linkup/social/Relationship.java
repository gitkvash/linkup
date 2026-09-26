package ge.kcamp.linkup.social;

import java.time.Instant;

/**
 * Where the caller stands with one other person, as their profile needs to show it.
 *
 * @param friendsSince when the two became friends; null unless {@link Kind#FRIENDS}, and
 *                     null for friendships accepted before V35 started recording it.
 * @param muted        whether the caller has muted this person's plans. Only ever true
 *                     for {@link Kind#FRIENDS}: a mute lives on the friendship.
 */
public record Relationship(Kind kind, Instant friendsSince, boolean muted) {

    public enum Kind {
        /** The caller, looking at themselves. */
        SELF,
        FRIENDS,
        /** The caller asked; the other person hasn't answered. */
        REQUEST_SENT,
        /** The other person asked the caller. */
        REQUEST_RECEIVED,
        /** Either of them blocked the other. Never shown: the profile answers 404. */
        BLOCKED,
        NONE
    }
}
