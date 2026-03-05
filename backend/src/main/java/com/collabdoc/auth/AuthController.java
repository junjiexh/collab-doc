package com.collabdoc.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;

    public AuthController(AuthService authService, JwtUtil jwtUtil) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null || username.isBlank() || password.length() < 4) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password (min 4 chars) required"));
        }
        try {
            User user = authService.register(username.trim(), password);
            String token = jwtUtil.generateToken(user.getId(), user.getUsername());
            return ResponseEntity.status(201)
                    .header(HttpHeaders.SET_COOKIE, buildCookie(token).toString())
                    .body(Map.of("id", user.getId(), "username", user.getUsername()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        return authService.login(username, password)
                .map(user -> {
                    String token = jwtUtil.generateToken(user.getId(), user.getUsername());
                    return ResponseEntity.ok()
                            .header(HttpHeaders.SET_COOKIE, buildCookie(token).toString())
                            .<Map<String, Object>>body(Map.of("id", user.getId(), "username", user.getUsername()));
                })
                .orElse(ResponseEntity.status(401).body(Map.of("error", "Invalid credentials")));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        ResponseCookie cookie = ResponseCookie.from("token", "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(
            @AuthenticationPrincipal UUID userId,
            HttpServletRequest request) {
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        // Read username from request attribute set by JwtAuthFilter
        String username = (String) request.getAttribute("authUsername");
        return ResponseEntity.ok(Map.of("id", userId, "username", username != null ? username : ""));
    }

    private ResponseCookie buildCookie(String token) {
        return ResponseCookie.from("token", token)
                .httpOnly(true)
                .path("/")
                .maxAge(jwtUtil.getExpirationSeconds())
                .sameSite("Lax")
                .build();
    }
}
