package ge.kcamp.linkup.identity.controller;

import ge.kcamp.linkup.identity.IdentityService;
import ge.kcamp.linkup.identity.PasswordResetService;
import ge.kcamp.linkup.identity.dto.AuthResponse;
import ge.kcamp.linkup.identity.dto.ForgotPasswordRequest;
import ge.kcamp.linkup.identity.dto.GoogleLoginRequest;
import ge.kcamp.linkup.identity.dto.LoginRequest;
import ge.kcamp.linkup.identity.dto.LogoutRequest;
import ge.kcamp.linkup.identity.dto.RefreshRequest;
import ge.kcamp.linkup.identity.dto.RegisterRequest;
import ge.kcamp.linkup.identity.dto.ResetPasswordRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final IdentityService identityService;
    private final PasswordResetService passwordResetService;

    public AuthController(IdentityService identityService, PasswordResetService passwordResetService) {
        this.identityService = identityService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = identityService.register(request.username(), request.password(), request.email());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = identityService.login(request.username(), request.password());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        AuthResponse response = identityService.loginWithGoogle(request.idToken());
        return ResponseEntity.ok(response);
    }

    /**
     * A new session for an old one. The client calls this when a request comes back 401
     * and retries the request with the new token; only a refusal here signs it out.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(identityService.refresh(request.refreshToken()));
    }

    /**
     * Ends the session the refresh token belongs to. Always 204, even for a token that is
     * missing, unknown or already revoked, so signing out can never fail on the client.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest request) {
        identityService.logout(request == null ? null : request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * Emails a six-digit code to the address if an account with a password uses it. Always
     * 204, whether or not one does, so the endpoint cannot be used to find out which
     * addresses have accounts.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestCode(request.email());
        return ResponseEntity.noContent().build();
    }

    /**
     * Sets a new password from the emailed code. 204 on success - the client then signs in
     * with the new password - and one 400 {@code INVALID_RESET_CODE} for every way it can
     * fail. Every session of the account is signed out.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.email(), request.code(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
