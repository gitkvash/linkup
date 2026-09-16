package ge.kcamp.linkup.social.exception;

public class GroupNotOwnedException extends RuntimeException {

    public GroupNotOwnedException() {
        super("Only the group owner can perform this action");
    }
}
