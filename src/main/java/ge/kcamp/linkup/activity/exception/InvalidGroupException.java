package ge.kcamp.linkup.activity.exception;

/**
 * GROUP visibility was asked for with no group, or with a group the creator is not a
 * member of. Deliberately says the same thing in both cases: "not a member" and "no such
 * group" are indistinguishable to the caller, so this cannot be used to find out which
 * group ids exist.
 */
public class InvalidGroupException extends RuntimeException {

    public InvalidGroupException(String message) {
        super(message);
    }

    public static InvalidGroupException missing() {
        return new InvalidGroupException("Pick a group to share this with.");
    }

    public static InvalidGroupException notAMember() {
        return new InvalidGroupException("You can only share a plan with a group you're in.");
    }
}
