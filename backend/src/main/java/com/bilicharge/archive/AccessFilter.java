package com.bilicharge.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
final class AccessFilter extends OncePerRequestFilter {
    static final String ADMIN_SESSION = "adminUsername";
    static final String CSRF_SESSION = "csrfToken";
    static final String REQUEST_ID = "requestId";

    private final ObjectMapper json;
    private final String monitorToken;

    AccessFilter(ObjectMapper json, @Value("${app.monitor-token:}") String monitorToken) {
        if (monitorToken.length() < 32) {
            throw new IllegalArgumentException("MONITOR_API_TOKEN must contain at least 32 characters");
        }
        this.json = json;
        this.monitorToken = monitorToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID, requestId);
        response.setHeader("X-Request-Id", requestId);
        String path = request.getRequestURI();

        if (path.equals("/internal") || path.startsWith("/internal/")) {
            if (!privateAddress(request.getRemoteAddr()) || !equal(monitorToken, request.getHeader("X-Monitor-Token"))) {
                reject(response, 403, "FORBIDDEN", "内部接口访问被拒绝", requestId);
                return;
            }
        } else if (path.equals("/api") || path.startsWith("/api/")) {
            if (!path.equals("/api/auth/login")) {
                HttpSession session = request.getSession(false);
                if (session == null || session.getAttribute(ADMIN_SESSION) == null) {
                    reject(response, 401, "UNAUTHORIZED", "请先登录", requestId);
                    return;
                }
                if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")
                        && !request.getMethod().equals("OPTIONS")
                        && !equal((String) session.getAttribute(CSRF_SESSION), request.getHeader("X-CSRF-Token"))) {
                    reject(response, 403, "CSRF_INVALID", "CSRF token 无效", requestId);
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    private boolean equal(String expected, String actual) {
        return expected != null && !expected.isBlank() && actual != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private boolean privateAddress(String raw) {
        try {
            InetAddress address = InetAddress.getByName(raw);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()) return true;
            byte[] bytes = address.getAddress();
            return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void reject(HttpServletResponse response, int status, String code, String message, String requestId)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(), new ApiError(code, message, requestId));
    }
}
