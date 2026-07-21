package com.agentflow.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * API Key 鉴权过滤器（R21）。
 *
 * <p>拦截 {@code /api/**} 请求，校验 {@code X-API-Key} header：
 * <ul>
 *   <li>Header 缺失 → 401（未认证）</li>
 *   <li>Header 存在但 Key 不在有效集合中 → 401</li>
 *   <li>有效 Key → 计算 SHA-256 hash 写入 request attribute {@code callerId}，
 *       下游 Controller 和 {@link WorkflowOwnershipChecker} 读取做所有权校验</li>
 * </ul>
 *
 * <p>注册方式（U13 AutoConfiguration 或 Spring Security）：
 * <pre>{@code
 * FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>();
 * registration.setFilter(new ApiKeyAuthFilter(validKeys));
 * registration.addUrlPatterns("/api/*");
 * }</pre>
 *
 * <p>v1 限硬编码 Key 集合（从 config 读取），v1.1 升级为数据库管理 + API 管理。
 */
public final class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** Request attribute 名：下游 Controller 通过此名获取调用者 identity（SHA-256 hash）。 */
    public static final String CALLER_ID_ATTR = "callerId";

    private static final String HEADER_NAME = "X-API-Key";
    private static final String API_PATH_PREFIX = "/api";

    /** 有效 Key 的 SHA-256 hash 集合（快速查重）。 */
    private final Set<String> validKeyHashes;

    /**
     * @param validKeys 有效的 API Key 集合（明文）。内部自动计算 SHA-256 hash。
     */
    public ApiKeyAuthFilter(Set<String> validKeys) {
        this.validKeyHashes = validKeys.stream()
                .map(ApiKeyAuthFilter::sha256)
                .collect(java.util.stream.Collectors.toSet());
        if (this.validKeyHashes.isEmpty()) {
            log.warn("ApiKeyAuthFilter 初始化时有效 Key 集合为空——所有 /api/** 请求将返回 401");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // 仅拦截 /api 路径
        if (!request.getRequestURI().startsWith(API_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(HEADER_NAME);
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("401 未认证：{} {} — 缺少 X-API-Key header", request.getMethod(), request.getRequestURI());
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "缺少 X-API-Key header");
            return;
        }

        String keyHash = sha256(apiKey);
        if (!validKeyHashes.contains(keyHash)) {
            log.warn("401 无效 Key：{} {} — hash={}", request.getMethod(), request.getRequestURI(),
                    keyHash.substring(0, 8) + "...");
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "无效的 X-API-Key");
            return;
        }

        // 鉴权通过：写入 callerId 供下游 Controller / WorkflowOwnershipChecker 使用
        request.setAttribute(CALLER_ID_ATTR, keyHash);
        filterChain.doFilter(request, response);
    }

    // ──────────────────────────── 辅助方法 ────────────────────────────

    /** 计算字符串的 SHA-256 hex 摘要。 */
    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}