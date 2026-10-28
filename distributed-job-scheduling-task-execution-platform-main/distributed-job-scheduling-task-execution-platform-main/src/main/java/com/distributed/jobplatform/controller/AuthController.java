package com.distributed.jobplatform.controller;

import com.distributed.jobplatform.dto.*;
import com.distributed.jobplatform.entity.User;
import com.distributed.jobplatform.repository.UserRepository;
import com.distributed.jobplatform.security.JwtTokenProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(AuthenticationManager authenticationManager,
                         JwtTokenProvider tokenProvider,
                         UserRepository userRepository,
                         PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        // SECURITY: public registration ALWAYS creates a USER account.
        // The client-supplied role field is deliberately ignored to prevent privilege
        // escalation (a request with role="ADMIN" must never create an ADMIN account).
        // ADMIN accounts are provisioned exclusively via DataInitializer using
        // app.admin.username/app.admin.password configuration (environment-only).
        User user = new User(
            request.getUsername(),
            passwordEncoder.encode(request.getPassword()),
            "USER"
        );
        userRepository.save(user);

        String token = tokenProvider.generateToken(user.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new AuthResponse(token, user.getUsername(), user.getRole()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        String token = tokenProvider.generateToken(authentication);

        User user = userRepository.findByUsername(request.getUsername()).orElseThrow();
        return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), user.getRole()));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }
        String username = authentication.getName();
        User user = userRepository.findByUsername(username).orElseThrow();
        return ResponseEntity.ok(Map.of(
            "username", user.getUsername(),
            "role", user.getRole(),
            "id", user.getId()
        ));
    }
}
