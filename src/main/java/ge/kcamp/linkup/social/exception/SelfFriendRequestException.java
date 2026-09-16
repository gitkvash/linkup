package ge.kcamp.linkup.social.exception;

public class SelfFriendRequestException extends RuntimeException {

    public SelfFriendRequestException() {
        super("Cannot send a friend request to yourself");
    }
}
