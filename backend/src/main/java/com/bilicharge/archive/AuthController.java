package com.bilicharge.archive;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
final class AuthController {
    private final String username;
    private final String passwordHash;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    AuthController(@Value("${app.admin.username:}") String username,
                   @Value("${app.admin.password-bcrypt:}") String passwordHash) {
        if (username.isBlank() || !passwordHash.matches("\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}")) {
            throw new IllegalArgumentException("ADMIN_USERNAME and ADMIN_PASSWORD_BCRYPT must be configured");
        }
        this.username = username;
        this.passwordHash = passwordHash;
    }

    @PostMapping("/login")
    ApiEnvelope<Map<String, String>> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        if (body == null || body.username() == null || body.password() == null
                || !username.equals(body.username()) || !passwords.matches(body.password(), passwordHash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String csrf = HexFormat.of().formatHex(bytes);
        session.setAttribute(AccessFilter.ADMIN_SESSION, username);
        session.setAttribute(AccessFilter.CSRF_SESSION, csrf);
        return new ApiEnvelope<>(Map.of("username", username, "csrfToken", csrf));
    }

    @GetMapping("/me")
    ApiEnvelope<Map<String, String>> me(HttpSession session) {
        return new ApiEnvelope<>(Map.of(
                "username", (String) session.getAttribute(AccessFilter.ADMIN_SESSION),
                "csrfToken", (String) session.getAttribute(AccessFilter.CSRF_SESSION)));
    }

    @PostMapping("/logout")
    ApiEnvelope<Map<String, Boolean>> logout(HttpSession session) {
        session.invalidate();
        return new ApiEnvelope<>(Map.of("loggedOut", true));
    }

    record LoginRequest(String username, String password) {}
}
