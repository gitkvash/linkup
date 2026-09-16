package ge.kcamp.linkup.activity.exception;

public class InvalidInviteeException extends RuntimeException {

    public InvalidInviteeException(int count) {
        super(count == 1
                ? "One of the people you invited isn't on your friends list."
                : count + " of the people you invited aren't on your friends list.");
    }
}
