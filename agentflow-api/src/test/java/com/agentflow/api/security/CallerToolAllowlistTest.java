package com.agentflow.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U14 CallerToolAllowlist 测试。
 */
class CallerToolAllowlistTest {

    private static final String CALLER_A = "aaa111";
    private static final String CALLER_B = "bbb222";

    @Test
    @DisplayName("授权 Tool 在列表中 → isAllowed=true")
    void authorizedToolIsAllowed() {
        CallerToolAllowlist allowlist = new CallerToolAllowlist(
                Map.of(CALLER_A, Set.of("finance-db", "risk-calc")));

        assertThat(allowlist.isAllowed(CALLER_A, "finance-db")).isTrue();
        assertThat(allowlist.isAllowed(CALLER_A, "risk-calc")).isTrue();
    }

    @Test
    @DisplayName("未授权 Tool → isAllowed=false")
    void unauthorizedToolIsDenied() {
        CallerToolAllowlist allowlist = new CallerToolAllowlist(
                Map.of(CALLER_A, Set.of("finance-db")));

        assertThat(allowlist.isAllowed(CALLER_A, "compliance")).isFalse();
    }

    @Test
    @DisplayName("未知 caller → isAllowed=false")
    void unknownCallerIsDenied() {
        CallerToolAllowlist allowlist = new CallerToolAllowlist(
                Map.of(CALLER_A, Set.of("finance-db")));

        assertThat(allowlist.isAllowed(CALLER_B, "finance-db")).isFalse();
    }

    @Test
    @DisplayName("通配符 * → 全部放行")
    void wildcardAllowsAll() {
        CallerToolAllowlist allowlist = new CallerToolAllowlist(
                Map.of(CALLER_A, Set.of("*")));

        assertThat(allowlist.isAllowed(CALLER_A, "anything")).isTrue();
        assertThat(allowlist.isAllowed(CALLER_A, "anything-else")).isTrue();
    }

    @Test
    @DisplayName("空 allowlist → 全局允许（向后兼容，不启用工具授权）")
    void emptyAllowlistAllowsAll() {
        CallerToolAllowlist allowlist = new CallerToolAllowlist(Map.of());

        assertThat(allowlist.isAllowed(CALLER_A, "any-tool")).isTrue();
        assertThat(allowlist.isAllowed(CALLER_B, "any-tool")).isTrue();
    }

    @Test
    @DisplayName("null allowlist → 全局允许")
    void nullAllowlistAllowsAll() {
        CallerToolAllowlist allowlist = new CallerToolAllowlist(null);

        assertThat(allowlist.isAllowed(CALLER_A, "any-tool")).isTrue();
    }

    @Test
    @DisplayName("null callerId 或 toolName → false")
    void nullParamsReturnFalse() {
        CallerToolAllowlist allowlist = new CallerToolAllowlist(
                Map.of(CALLER_A, Set.of("finance-db")));

        assertThat(allowlist.isAllowed(null, "finance-db")).isFalse();
        assertThat(allowlist.isAllowed(CALLER_A, null)).isFalse();
    }

    @Test
    @DisplayName("allowedTools 返回 caller 的授权 Tool 集合")
    void allowedToolsReturnsSet() {
        CallerToolAllowlist allowlist = new CallerToolAllowlist(
                Map.of(CALLER_A, Set.of("t1", "t2")));

        assertThat(allowlist.allowedTools(CALLER_A)).containsExactlyInAnyOrder("t1", "t2");
        assertThat(allowlist.allowedTools(CALLER_B)).isEmpty();
    }

    // ─────────────────── v1.1 R21：DB 授权仓储叠加 ───────────────────

    @Test
    @DisplayName("R21：config ∪ DB 相加授权；任一源命中即放行")
    void repoGrantsExtendConfig() {
        InMemoryToolGrantRepository repo = new InMemoryToolGrantRepository();
        repo.grant(CALLER_B, "db-tool", "admin");
        // 空 config + 非空 DB → DB 侧启用校验
        CallerToolAllowlist allowlist = new CallerToolAllowlist(Map.of(), repo);

        assertThat(allowlist.isAllowed(CALLER_B, "db-tool")).isTrue();   // DB 命中
        assertThat(allowlist.isAllowed(CALLER_A, "db-tool")).isFalse();  // 未授权
        assertThat(allowlist.isAllowed(CALLER_A, "other")).isFalse();
    }

    @Test
    @DisplayName("R1：config 命中即使 DB 未接入也放行（向后兼容）；allowedTools 合并两源")
    void configStillWorksAndMerges() {
        InMemoryToolGrantRepository repo = new InMemoryToolGrantRepository();
        repo.grant(CALLER_B, "db-tool", "admin");
        CallerToolAllowlist allowlist = new CallerToolAllowlist(Map.of(CALLER_A, Set.of("cfg-tool")), repo);

        assertThat(allowlist.isAllowed(CALLER_A, "cfg-tool")).isTrue();   // config 源
        assertThat(allowlist.isAllowed(CALLER_B, "db-tool")).isTrue();    // DB 源
        assertThat(allowlist.isAllowed(CALLER_A, "db-tool")).isFalse();   // 两源都没有
        assertThat(allowlist.allowedTools(CALLER_A)).containsExactlyInAnyOrder("cfg-tool");
        assertThat(allowlist.allowedTools(CALLER_B)).containsExactlyInAnyOrder("db-tool");
    }

    @Test
    @DisplayName("R1：config 与 DB 均空 → 全局允许（v1 兼容）")
    void repoEmptyStillAllowsAll() {
        CallerToolAllowlist allowlist = new CallerToolAllowlist(Map.of(), new InMemoryToolGrantRepository());
        assertThat(allowlist.isAllowed(CALLER_A, "anything")).isTrue();
    }

    @Test
    @DisplayName("R1：DB 有记录后即使 config 空，未授权 caller 拒绝")
    void repoEnabledDeniesUnknown() {
        InMemoryToolGrantRepository repo = new InMemoryToolGrantRepository();
        repo.grant(CALLER_A, "t1", "admin");
        CallerToolAllowlist allowlist = new CallerToolAllowlist(Map.of(), repo);
        assertThat(allowlist.isAllowed(CALLER_A, "t1")).isTrue();     // 有授权
        assertThat(allowlist.isAllowed(CALLER_A, "t2")).isFalse();    // 同 caller 另一 tool 未授权
        assertThat(allowlist.isAllowed(CALLER_B, "t1")).isFalse();    // 别的 caller 未授权
    }
}