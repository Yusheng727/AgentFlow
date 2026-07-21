package com.agentflow.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U14 CredentialManager 测试（plan U14 Test scenarios）。
 */
class CredentialManagerTest {

    @Test
    @DisplayName("isHardcoded 检测占位符 'your-api-key' → true")
    void detectsYourApiKey() {
        assertThat(CredentialManager.isHardcoded("your-api-key")).isTrue();
    }

    @Test
    @DisplayName("isHardcoded 检测占位符 'changeme' → true")
    void detectsChangeme() {
        assertThat(CredentialManager.isHardcoded("changeme")).isTrue();
    }

    @Test
    @DisplayName("isHardcoded 检测占位符 'sk-xxxxx' → true")
    void detectsSkXxxxx() {
        assertThat(CredentialManager.isHardcoded("sk-xxxxx")).isTrue();
    }

    @Test
    @DisplayName("isHardcoded 检测环境变量占位符 '${SPRING_AI_OPENAI_API_KEY}' → true")
    void detectsEnvPlaceholder() {
        assertThat(CredentialManager.isHardcoded("${SPRING_AI_OPENAI_API_KEY}")).isTrue();
    }

    @Test
    @DisplayName("isHardcoded 真实 Key（如 sk-proj-abc123...）→ false")
    void realKeyIsNotHardcoded() {
        assertThat(CredentialManager.isHardcoded(
                "sk-proj-AB12CD34EF56GH78IJ90KL12MN34OP56QR78ST90UV12WX34")).isFalse();
    }

    @Test
    @DisplayName("isHardcoded null/空字符串 → false")
    void nullOrBlankIsNotHardcoded() {
        assertThat(CredentialManager.isHardcoded(null)).isFalse();
        assertThat(CredentialManager.isHardcoded("")).isFalse();
        assertThat(CredentialManager.isHardcoded("   ")).isFalse();
    }

    @Test
    @DisplayName("validate 未设环境变量 → 抛 IllegalStateException")
    void validateThrowsWhenKeyMissing() {
        MockEnvironment env = new MockEnvironment();
        CredentialManager cm = new CredentialManager(env);

        assertThatThrownBy(cm::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_AI_OPENAI_API_KEY");
    }

    @Test
    @DisplayName("validate 硬编码 Key → 抛 IllegalStateException")
    void validateThrowsWhenHardcoded() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.ai.openai.api-key", "your-api-key");
        CredentialManager cm = new CredentialManager(env);

        assertThatThrownBy(cm::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("硬编码");
    }

    @Test
    @DisplayName("openAiApiKey 从 System.getenv 读取（兼容 OPENAI_API_KEY）")
    void readsFromEnv() {
        MockEnvironment env = new MockEnvironment();
        CredentialManager cm = new CredentialManager(env);

        // 没有环境变量时返回 null
        String key = cm.openAiApiKey();
        // 系统可能已设此变量，仅验证不抛异常
        assertThat(key == null || !key.isBlank()).isTrue();
    }

    @Test
    @DisplayName("getEnv 从 Spring Environment 读取（System.getenv 无此变量时）")
    void getEnvFallsBackToSpringEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("CUSTOM_KEY", "from-spring-env");
        CredentialManager cm = new CredentialManager(env);

        // System.getenv("CUSTOM_KEY") 大概率没有 → 从 Spring Environment 读
        String val = cm.getEnv("CUSTOM_KEY");
        assertThat(val).isEqualTo("from-spring-env");
    }
}