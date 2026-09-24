package ge.kcamp.linkup.social.exception;

/**
 * The user being added to a group is on the other side of a block from the owner. The
 * message doesn't say which of them placed it.
 */
public class GroupMemberNotFriendException extends RuntimeException {

    public GroupMemberNotFriendException() {
        super("You can't add this person to a group.");
    }
}
