package ge.kcamp.linkup.identity.mail;

/**
 * Gets a reset code to a person. Implementations must return promptly and must never
 * throw: the caller answers the same 204 whether or not an account exists, and a send that
 * blocked or failed only for real accounts would tell a stranger which addresses have one.
 */
public interface PasswordResetMailer {

    /**
     * @param code the plain six digits. Implementations must not log it outside the dev
     *             profile: a code in a log is a password reset for anyone who reads logs.
     */
    void sendResetCode(String toEmail, String code, int validForMinutes);
}
