package com.agentflow.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U7 R22：{@link ColumnEncryptors} 工厂测试——宽松（缺/非法 → Noop）、strict fail-closed
 * （缺/非法 → 抛异常）、配 key → AES-GCM。经 package-private {@code build(key, strict)} 直测
 * （避免测试改写进程环境变量），{@code fromEnv/fromEnvStrict} 仅是 {@code System.getenv} 委托。
 */
class ColumnEncryptorsTest {

    private static final String VALID_KEY = Base64.getEncoder().encodeToString(
            "abcdefghijklmnopqrstuvwxyz012345".getBytes(StandardCharsets.UTF_8)); // 32B

    // ───────────────────────── 宽松模式（dev/demo） ─────────────────────────

    @Test
    @DisplayName("build(null, false) → Noop（dev/demo 明文落库，兼容）")
    void lenientMissingReturnsNoop() {
        assertThat(ColumnEncryptors.build(null, false)).isInstanceOf(NoopColumnEncryptor.class);
    }

    @Test
    @DisplayName("build(非法key, false) → Noop + 不抛（宽松）")
    void lenientInvalidReturnsNoop() {
        assertThat(ColumnEncryptors.build("not-base64", false)).isInstanceOf(NoopColumnEncryptor.class);
    }

    @Test
    @DisplayName("build(合法key, false) → AES-GCM")
    void lenientValidReturnsAes() {
        assertThat(ColumnEncryptors.build(VALID_KEY, false)).isInstanceOf(AesGcmColumnEncryptor.class);
    }

    // ──────────────────────── 严格模式（生产 fail-closed） ────────────────────────

    @Test
    @DisplayName("build(null, true) → 抛异常（fail-closed 拒绝明文落库）")
    void strictMissingThrows() {
        assertThatThrownBy(() -> ColumnEncryptors.build(null, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ColumnEncryptors.ENV_KEY);
    }

    @Test
    @DisplayName("build(非法key, true) → 抛异常（fail-closed）")
    void strictInvalidThrows() {
        assertThatThrownBy(() -> ColumnEncryptors.build("bad-key", true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("build(合法key, true) → AES-GCM")
    void strictValidReturnsAes() {
        assertThat(ColumnEncryptors.build(VALID_KEY, true)).isInstanceOf(AesGcmColumnEncryptor.class);
    }
}
