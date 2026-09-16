package ge.kcamp.linkup.activity.web;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Resolves the client-supplied IANA zone id, falling back to UTC.
 * <p>
 * An unknown or malformed id is treated as absent rather than rejected: the zone is a
 * hint for interpreting free text, and refusing the whole request because a device
 * reported an odd zone name would be worse than being an hour off.
 */
public final class RequestZone {

    private RequestZone() {
    }

    public static ZoneId resolve(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timeZone.strip());
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
