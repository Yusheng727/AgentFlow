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
 * <h3>v1.1 R21 升级</h3>
 * <p>config 硬编码之上叠加 {@link ToolGrantRepository}（{@code caller_tool_grants} 表 + 管理 API）：
 * 授权判定 = config 静态授权 ∪ DB 动态授权，两者均启用则任一放行；config 与 DB 全空 → 全局允许
 * （v1 向后兼容，不启用工具授权）。DB 有记录即触发 DB 侧校验（存在即强制的 least-surprise 语义）。
 *
 * <p>v1 限硬编码映射（config/环境变量），无管理 UI；v1.1 升级为 {@code caller_tool_grants} 表。
 */
public final class CallerToolAllowlist {

    private static final Logger log = LoggerFactory.getLogger(CallerToolAllowlist.class);

    /** callerId (SHA-256 hash) → 授权 Tool 集合 */
    private final Map<String, Set<String>> allowlist;

    /** 动态授权仓储（v1.1 R21，可空——null 时仅 config 生效） */
    private final ToolGrantRepository grants;

    /**
     * @param allowlist callerId → Tool 名集合（不可变视图）
     */
    public CallerToolAllowlist(Map<String, Set<String>> allowlist) {
        this(allowlist, null);
    }

    /**
     * @param allowlist config 静态授权（可空/空 map）
     * @param grants    DB 授权仓储（可空；非空时并入授权判定）
     */
    public CallerToolAllowlist(Map<String, Set<String>> allowlist, ToolGrantRepository grants) {
        this.allowlist = allowlist != null
                ? deepCopy(allowlist)
                : Collections.emptyMap();
        this.grants = grants;
        log.info("CallerToolAllowlist 初始化：{} 个 config caller / {} 条 config 授权 / DB 仓储 {}",
                this.allowlist.size(),
                this.allowlist.values().stream().mapToInt(Set::size).sum(),
                grants != null ? "已接入（" + grants.totalGrantCount() + " 条）" : "未接入");
    }

    // ──────────────────────────── 公共 API ────────────────────────────

    /**
     * 校验 callerId 是否有权使用 toolName。
     *
     * <p>语义：config 与 DB 全空 → 全局允许（v1 兼容）；否则任一源授权（config ∪ DB）即放行，
     * 两处都没有 → 拒绝。DB 有记录即启用校验（存在即 enforce）。
     *
     * @param callerId 调用者 SHA-256 hash（来自 X-API-Key）
     * @param toolName Tool 名称
     * @return true 若有授权
     */
    public boolean isAllowed(String callerId, String toolName) {
        if (callerId == null || toolName == null) return false;
        boolean configEnabled = !allowlist.isEmpty();
        boolean repoEnabled = grants != null && grants.totalGrantCount() > 0;
        if (!configEnabled && !repoEnabled) {
            // 空 allowlist = 全局允许（v1 不启用工具授权时向后兼容）
            return true;
        }
        if (configEnabled && hasTool(allowlist.get(callerId), toolName)) {
            return true;
        }
        if (repoEnabled && grants.isGranted(callerId, toolName)) {
            return true;
        }
        if (configEnabled || repoEnabled) {
            log.debug("Caller {} 未授权 tool={}", maskCaller(callerId), toolName);
        }
        return false;
    }

    /**
     * 获取某 caller 的所有授权 Tool（config ∪ DB，调试用）。
     *
     * @return 不可变 Set，caller 未知则返回空 Set
     */
    public Set<String> allowedTools(String callerId) {
        if (callerId == null) return Set.of();
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (!allowlist.isEmpty()) {
            Set<String> configTools = allowlist.get(callerId);
            if (configTools != null) merged.addAll(configTools);
        }
        if (grants != null) {
            merged.addAll(grants.findGrantedTools(callerId));
        }
        return Collections.unmodifiableSet(merged);
    }

    /** allowlist 中 caller 数量。 */
    public int callerCount() {
        return allowlist.size();
    }

    // ──────────────────────────── 辅助方法 ────────────────────────────

    private static boolean hasTool(Set<String> tools, String toolName) {
        return tools != null && (tools.contains(toolName) || tools.contains("*"));
    }

    private static Map<String, Set<String>> deepCopy(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new HashMap<>();
        source.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
        return Collections.unmodifiableMap(copy);
    }

    private static String maskCaller(String id) {
        return id == null ? "null" : id.substring(0, Math.min(8, id.length())) + "...";
    }
}