package com.agentflow.api.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-caller Tool 授权管理器（v4.3 工具级授权）。
 *
 * <p>从 config 读取 {@code agentflow.security.tool-allowlist} 配置（Map 结构），
 * 提供 {@link #isAllowed} 校验指定调用者是否有权使用指定 Tool。
 * 用于 U1 {@code SemanticValidator} 阶段校验 YAML 节点引用的 {@code @Tool}。
 *
 * <h3>Config 格式（application.yml）</h3>
 * <pre>{@code
 * agentflow:
 *   security:
 *     tool-allowlist:
 *       "[caller-hash-1]": ["finance-db-query", "risk-calculator"]
 *       "[caller-hash-2]": ["compliance-checker"]
 * }</pre>
 *
 * <p>v1 限硬编码映射（config/环境变量），无管理 UI；v1.1 升级为 {@code caller_tool_grants} 表。
 */
public final class CallerToolAllowlist {

    private static final Logger log = LoggerFactory.getLogger(CallerToolAllowlist.class);

    /** callerId (SHA-256 hash) → 授权 Tool 集合 */
    private final Map<String, Set<String>> allowlist;

    /**
     * @param allowlist callerId → Tool 名集合（不可变视图）
     */
    public CallerToolAllowlist(Map<String, Set<String>> allowlist) {
        this.allowlist = allowlist != null
                ? deepCopy(allowlist)
                : Collections.emptyMap();
        log.info("CallerToolAllowlist 初始化：{} 个 caller，共 {} 条 tool 授权",
                this.allowlist.size(),
                this.allowlist.values().stream().mapToInt(Set::size).sum());
    }

    // ──────────────────────────── 公共 API ────────────────────────────

    /**
     * 校验 callerId 是否有权使用 toolName。
     *
     * @param callerId 调用者 SHA-256 hash（来自 X-API-Key）
     * @param toolName Tool 名称
     * @return true 若有授权
     */
    public boolean isAllowed(String callerId, String toolName) {
        if (callerId == null || toolName == null) return false;
        if (allowlist.isEmpty()) {
            // 空 allowlist = 全局允许（v1 不启用工具授权时向后兼容）
            return true;
        }
        Set<String> tools = allowlist.get(callerId);
        if (tools == null) {
            log.debug("Caller {} 无 tool 授权记录", maskCaller(callerId));
            return false;
        }
        boolean allowed = tools.contains(toolName) || tools.contains("*");
        if (!allowed) {
            log.debug("Caller {} 未授权 tool={}", maskCaller(callerId), toolName);
        }
        return allowed;
    }

    /**
     * 获取某 caller 的所有授权 Tool（调试用）。
     *
     * @return 不可变 Set，caller 未知则返回空 Set
     */
    public Set<String> allowedTools(String callerId) {
        if (callerId == null || allowlist.isEmpty()) return Set.of();
        Set<String> tools = allowlist.get(callerId);
        return tools != null ? Collections.unmodifiableSet(tools) : Set.of();
    }

    /** allowlist 中 caller 数量。 */
    public int callerCount() {
        return allowlist.size();
    }

    // ──────────────────────────── 辅助方法 ────────────────────────────

    private static Map<String, Set<String>> deepCopy(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new HashMap<>();
        source.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
        return Collections.unmodifiableMap(copy);
    }

    private static String maskCaller(String id) {
        return id == null ? "null" : id.substring(0, Math.min(8, id.length())) + "...";
    }
}