package ge.kcamp.linkup.social.exception;

public class FriendshipBlockedException extends RuntimeException {

    public FriendshipBlockedException() {
        super("This friendship is blocked");
    }
}
