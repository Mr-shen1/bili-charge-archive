package com.bilicharge.archive;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class AuthHttpTest extends MySqlTestBase {
    @Autowired TestRestTemplate http;

    @Test
    void anonymousAndCsrfRules() {
        for (String path : new String[] {"/api/dynamics", "/api/dynamics/123", "/api/media/dynamics/123/0", "/api/admin/groups"}) {
            ResponseEntity<Map> response = http.getForEntity(path, Map.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).containsKeys("code", "message", "requestId");
        }
        ResponseEntity<Map> bad = login("wrong");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(bad.getBody()).doesNotContainKey("password");
        assertThat(bad.getBody().toString()).doesNotContain("wrong", PASSWORD, "m1-test-token");
        assertThat(bad.getBody().get("requestId")).isNotEqualTo("");

        ResponseEntity<Map> good = login(PASSWORD);
        assertThat(good.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cookie = good.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).contains("Secure", "HttpOnly", "SameSite=Strict");
        String sessionCookie = cookie.split(";", 2)[0];
        Map data = (Map) good.getBody().get("data");
        String csrf = (String) data.get("csrfToken");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        ResponseEntity<Map> me = http.exchange("/api/auth/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map) me.getBody().get("data")).get("csrfToken")).isEqualTo(csrf);

        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> noCsrf = http.exchange("/api/admin/groups", HttpMethod.POST, new HttpEntity<>(Map.of("name", "test"), headers), Map.class);
        assertThat(noCsrf.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(noCsrf.getBody().get("code")).isEqualTo("CSRF_INVALID");

        headers.add("X-CSRF-Token", csrf);
        ResponseEntity<Map> logout = http.exchange("/api/auth/logout", HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.exchange("/api/auth/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void internalTokenIsRequired() {
        ResponseEntity<Map> denied = http.getForEntity("/internal/ups", Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Monitor-Token", "m1-test-token-32-characters-long!");
        ResponseEntity<Map> allowedThroughFilter = http.exchange("/internal/ups", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(allowedThroughFilter.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowedThroughFilter.getBody()).containsKey("data");
    }

    private ResponseEntity<Map> login(String password) {
        return http.postForEntity("/api/auth/login", Map.of("username", USERNAME, "password", password), Map.class);
    }
}
