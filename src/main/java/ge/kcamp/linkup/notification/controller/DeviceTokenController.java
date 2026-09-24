package ge.kcamp.linkup.notification.controller;

import ge.kcamp.linkup.UserContext;
import ge.kcamp.linkup.notification.DeviceTokenService;
import ge.kcamp.linkup.notification.dto.RegisterDeviceTokenRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications/device-token")
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    public DeviceTokenController(DeviceTokenService deviceTokenService) {
        this.deviceTokenService = deviceTokenService;
    }

    /**
     * Claims this device for the caller, taking it away from any previous owner - which
     * is what actually happens when someone signs out of the app and someone else signs
     * in on the same phone. The old (user_id, fcm_token) primary key allowed several
     * owners at once, so a token could be registered under a second account and that
     * account's notifications would be delivered to the first user's device.
     *
     * <p>Taking it from the previous owner writes across two users, which no row-level
     * policy can express, so that one statement runs on the system role inside
     * {@link DeviceTokenService#claim}; nothing here chooses a role.
     */
    @PostMapping
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterDeviceTokenRequest request) {
        UUID userId = UserContext.getUserId();
        deviceTokenService.claim(userId, request.token(), request.platform());
        return ResponseEntity.noContent().build();
    }

    /**
     * Called on sign-out. Without it, a logged-out device kept receiving pushes for the
     * account that had been signed in on it.
     * <p>
     * Releases the token from the caller's account only, and 204 whether or not it was
     * there. This ran as the system role and deleted by token alone, so any signed-in user
     * who knew another device's token could silence it.
     */
    @DeleteMapping
    public ResponseEntity<Void> unregister(@RequestParam String token) {
        deviceTokenService.release(UserContext.getUserId(), token);
        return ResponseEntity.noContent().build();
    }
}
