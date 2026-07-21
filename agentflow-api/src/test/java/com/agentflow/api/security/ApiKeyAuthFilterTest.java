package com.agentflow.api.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U14 ApiKeyAuthFilter 测试（plan U14 Test scenarios）。
 */
class ApiKeyAuthFilterTest {

    private static final String VALID_KEY = "agentflow-test-key-12345";
    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter(Set.of(VALID_KEY));
    }

    @Test
    @DisplayName("无 X-API-Key header → 401")
    void missingApiKeyReturns401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/workflows");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getErrorMessage()).contains("X-API-Key");
    }

    @Test
    @DisplayName("无效 X-API-Key → 401")
    void invalidApiKeyReturns401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workflows/wf-1/status");
        request.addHeader("X-API-Key", "wrong-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("有效 X-API-Key → 放行，设置 callerId attribute")
    void validApiKeyPasses() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/workflows");
        request.addHeader("X-API-Key", VALID_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        String callerId = (String) request.getAttribute(ApiKeyAuthFilter.CALLER_ID_ATTR);
        assertThat(callerId).isNotNull();
        assertThat(callerId).hasSize(64); // SHA-256 hex = 64 chars
    }

    @Test
    @DisplayName("非 /api 路径 → 跳过鉴权（不检查 header）")
    void nonApiPathBypasses() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        // 不设 X-API-Key header
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(ApiKeyAuthFilter.CALLER_ID_ATTR)).isNull();
    }

    @Test
    @DisplayName("空字符串 X-API-Key → 401（同缺失）")
    void blankApiKeyReturns401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workflows/wf-1/status");
        request.addHeader("X-API-Key", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("sha256 相同明文 Key 产生相同 hash")
    void sameKeyProducesSameHash() {
        String hash1 = ApiKeyAuthFilter.sha256(VALID_KEY);
        String hash2 = ApiKeyAuthFilter.sha256(VALID_KEY);
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("sha256 不同 Key 产生不同 hash")
    void differentKeysProduceDifferentHashes() {
        String hash1 = ApiKeyAuthFilter.sha256("key-a");
        String hash2 = ApiKeyAuthFilter.sha256("key-b");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("CALLER_ID_ATTR 常量值为 'callerId'")
    void callerIdConstant() {
        assertThat(ApiKeyAuthFilter.CALLER_ID_ATTR).isEqualTo("callerId");
    }
}