package com.agentflow.api.security;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 工具授权管理 API（v1.1 R21）：{@code caller_tool_grants} 表的运行时读写入口。
 *
 * <p><b>安全模型</b>：变更（grant/revoke）仅限 admin——admin 由独立 API Key（env
 * {@code AGENTFLOW_ADMIN_API_KEY}，逗号分隔多值）识别，与普通 X-API-Key 一样经 SHA-256 哈希比对。
 * 普通 caller 可读自己的授权，不可改别人、也不可改自己（防自授予特权工具）。
 * 未配置 admin key → 所有变更端点 403（安全默认），读取仍可用。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolGrantController {

    private static final Logger log = LoggerFactory.getLogger(ToolGrantController.class);

    private final ToolGrantRepository repository;
    private final Set<String> adminHashes;

    public ToolGrantController(ToolGrantRepository repository,
                               @Value("${agentflow.admin.api-keys:}") String adminKeysCsv) {
        this.repository = repository;
        this.adminHashes = adminKeysCsv == null || adminKeysCsv.isBlank()
                ? Collections.emptySet()
                : hashKeys(adminKeysCsv);
        if (adminHashes.isEmpty()) {
            log.warn("ToolGrantController：未配置 AGENTFLOW_ADMIN_API_KEY——grant/revoke 全部 403（仅允许读取）");
        }
    }

    /** 记录类 grant 请求体。 */
    record GrantRequest(String callerId, String toolName) {}

    @GetMapping("/grants")
    public ResponseEntity<Set<String>> listGrants(
            @RequestParam(required = false) String caller, HttpServletRequest httpRequest) {
        if (caller != null && !caller.isBlank()) {
            // admin 可查任意 caller；非 admin 403
            if (!isAdmin(httpRequest)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            return ResponseEntity.ok(repository.findGrantedTools(caller));
        }
        // 普通 caller 只读自己的
        return ResponseEntity.ok(repository.findGrantedTools(callerIdOf(httpRequest)));
    }

    @PostMapping("/grants")
    public ResponseEntity<String> grant(@RequestBody GrantRequest req, HttpServletRequest httpRequest) {
        if (req == null || req.callerId() == null || req.callerId().isBlank()
                || req.toolName() == null || req.toolName().isBlank()) {
            return ResponseEntity.badRequest().body("参数缺失：callerId / toolName");
        }
        if (!isAdmin(httpRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("仅 admin 可执行（需 AGENTFLOW_ADMIN_API_KEY）");
        }
        repository.grant(req.callerId(), req.toolName(), callerIdOf(httpRequest));
        log.info("grant: caller={} tool={} by={}", maskCaller(req.callerId()), req.toolName(), maskCaller(callerIdOf(httpRequest)));
        return ResponseEntity.status(HttpStatus.CREATED).body("granted");
    }

    @DeleteMapping("/grants/{callerId}/{toolName}")
    public ResponseEntity<Void> revoke(@PathVariable String callerId,
                                       @PathVariable String toolName,
                                       HttpServletRequest httpRequest) {
        if (!isAdmin(httpRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        repository.revoke(callerId, toolName);
        log.info("revoked: caller={} tool={}", maskCaller(callerId), toolName);
        return ResponseEntity.noContent().build();
    }

    // ──────────────────────────── 辅助 ────────────────────────────

    private boolean isAdmin(HttpServletRequest httpRequest) {
        return adminHashes.contains(callerIdOf(httpRequest));
    }

    private static String callerIdOf(HttpServletRequest httpRequest) {
        Object c = httpRequest.getAttribute(ApiKeyAuthFilter.CALLER_ID_ATTR);
        return c != null ? c.toString() : "unknown";
    }

    private static Set<String> hashKeys(String csv) {
        Set<String> hashes = new LinkedHashSet<>();
        Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .forEach(k -> hashes.add(ApiKeyAuthFilter.sha256(k)));
        return Collections.unmodifiableSet(hashes);
    }

    private static String maskCaller(String id) {
        return id == null ? "null" : id.substring(0, Math.min(8, id.length())) + "...";
    }
}