package com.bilicharge.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalAccessTest {
    private static final String TOKEN = "m1-test-token-32-characters-long!";
    private final AccessFilter filter = new AccessFilter(new ObjectMapper(), TOKEN);

    @Test
    void publicSourceIsRejectedEvenWithValidToken() throws Exception {
        MockHttpServletRequest request = request("8.8.8.8", TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("FORBIDDEN", "requestId");
    }

    @Test
    void privateSourceNeedsToken() throws Exception {
        MockHttpServletRequest missing = request("172.16.0.2", null);
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(missing, denied, new MockFilterChain());
        assertThat(denied.getStatus()).isEqualTo(403);

        MockHttpServletRequest valid = request("172.16.0.2", TOKEN);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        filter.doFilter(valid, allowed, chain);
        assertThat(chain.getRequest()).isSameAs(valid);
        assertThat(allowed.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest request(String ip, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/probe");
        request.setRemoteAddr(ip);
        if (token != null) request.addHeader("X-Monitor-Token", token);
        return request;
    }
}
