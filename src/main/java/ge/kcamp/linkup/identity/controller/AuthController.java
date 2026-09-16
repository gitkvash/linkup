package ge.kcamp.linkup.identity.controller;

import ge.kcamp.linkup.identity.IdentityService;
import ge.kcamp.linkup.identity.dto.AuthResponse;
import ge.kcamp.linkup.identity.dto.GoogleLoginRequest;
import ge.kcamp.linkup.identity.dto.LoginRequest;
import ge.kcamp.linkup.identity.dto.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final IdentityService identityService;

    public AuthController(IdentityService identityService) {
        this.identityService = identityService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = identityService.register(request.username(), request.password());
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
}
