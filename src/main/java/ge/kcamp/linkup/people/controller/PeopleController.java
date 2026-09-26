package ge.kcamp.linkup.people.controller;

import ge.kcamp.linkup.UserContext;
import ge.kcamp.linkup.activity.ActivityFeedItem;
import ge.kcamp.linkup.people.PersonProfileService;
import ge.kcamp.linkup.people.UserStatsService;
import ge.kcamp.linkup.people.dto.UserProfileDto;
import ge.kcamp.linkup.people.dto.UserStatsDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@RestController
public class PeopleController {

    private final PersonProfileService personProfileService;
    private final UserStatsService userStatsService;

    public PeopleController(PersonProfileService personProfileService, UserStatsService userStatsService) {
        this.personProfileService = personProfileService;
        this.userStatsService = userStatsService;
    }

    /**
     * Someone's profile as the caller sees it. 404 for an account that doesn't exist and,
     * indistinguishably, for a pair where either has blocked the other.
     */
    @GetMapping("/api/v1/users/{userId}/profile")
    public ResponseEntity<UserProfileDto> profile(@PathVariable UUID userId) {
        return personProfileService.profile(UserContext.getUserId(), userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The plans under a profile's tabs: {@code upcoming} - theirs, hosting or going, that
     * the caller may see - or {@code together}, the ones the two did. 404 as above.
     */
    @GetMapping("/api/v1/users/{userId}/activities")
    public ResponseEntity<List<ActivityFeedItem>> activities(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "upcoming") String scope) {
        PersonProfileService.ActivityScope parsed = switch (scope) {
            case "upcoming" -> PersonProfileService.ActivityScope.UPCOMING;
            case "together" -> PersonProfileService.ActivityScope.TOGETHER;
            default -> throw new IllegalArgumentException("scope must be 'upcoming' or 'together'");
        };
        return personProfileService.activities(UserContext.getUserId(), userId, parsed)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The caller's own year. {@code tz} is an IANA zone ("Asia/Tbilisi"); every bucket -
     * month, weekday, time of day - is in it. Missing or unknown falls back to UTC rather
     * than failing, since the counts are still right and only the edges move.
     */
    @GetMapping("/api/v1/me/stats")
    public ResponseEntity<UserStatsDto> stats(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String tz) {
        ZoneId zone = zoneOrUtc(tz);
        int resolved = year == null ? Year.now(zone).getValue() : year;
        if (resolved < 2000 || resolved > 2100) {
            throw new IllegalArgumentException("year must be between 2000 and 2100");
        }
        return ResponseEntity.ok(userStatsService.stats(UserContext.getUserId(), resolved, zone));
    }

    private static ZoneId zoneOrUtc(String tz) {
        if (tz == null || tz.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(tz.strip());
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
