package ge.kcamp.linkup.social.exception;

public class FriendRequestNotFoundException extends RuntimeException {

    public FriendRequestNotFoundException() {
        super("No matching pending friend request");
    }
}
