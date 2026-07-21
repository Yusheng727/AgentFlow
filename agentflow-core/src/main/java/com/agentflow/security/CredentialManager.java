package com.agentflow.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.Map;
import java.util.Objects;

/**
 * LLM API Key 凭证管理器（R22）。
 *
 * <p>从环境变量读取 LLM API Key，启动时校验：
 * <ul>
 *   <li>Key 非空——未设置则启动失败</li>
 *   <li>Key 未硬编码在 application.yml / application.properties——检测到硬编码则启动失败</li>
 * </ul>
 *
 * <p>用法：Spring Bean，注入 Environment，构造后调用 {@link #validate()}。
 * U13 AutoConfiguration 负责创建并校验。
 *
 * <h3>支持的凭证</h3>
 * <ul>
 *   <li>{@code SPRING_AI_OPENAI_API_KEY} — OpenAI</li>
 *   <li>预留扩展：<a href="https://docs.spring.ai/2.0.0/reference/api/chatmodel.html">Spring AI ChatModel 凭证配置</a></li>
 * </ul>
 */
public final class CredentialManager {

    private static final Logger log = LoggerFactory.getLogger(CredentialManager.class);

    /** 硬编码 Key 的典型占位符模式（检测 yml 中写了但未替换的占位值）。 */
    private static final String[] HARDCODED_PATTERNS = {
            "your-api-key", "your_key", "changeme", "sk-xxxxx",
            "${SPRING_AI_OPENAI_API_KEY}", "${OPENAI_API_KEY}"
    };

    private final Environment env;

    public CredentialManager(Environment env) {
        this.env = Objects.requireNonNull(env, "env");
    }

    // ──────────────────────────── API Key 读取 ────────────────────────────

    /** 读取 OpenAI API Key（从环境变量 SPRING_AI_OPENAI_API_KEY）。 */
    public String openAiApiKey() {
        String key = System.getenv("SPRING_AI_OPENAI_API_KEY");
        if (key == null) {
            key = System.getenv("OPENAI_API_KEY"); // 兼容旧变量名
        }
        if (key == null) {
            key = env.getProperty("spring.ai.openai.api-key");
        }
        return key;
    }

    /** 读取任意命名的环境变量。 */
    public String getEnv(String name) {
        String key = System.getenv(name);
        if (key == null) {
            key = env.getProperty(name);
        }
        return key;
    }

    // ───────────────────────── 启动时安全校验 ─────────────────────────

    /**
     * 启动时校验：所有已知 Key 非空且未硬编码。
     *
     * @throws IllegalStateException 若 Key 为空或硬编码
     */
    public void validate() {
        validateKey("SPRING_AI_OPENAI_API_KEY", openAiApiKey());
        log.info("CredentialManager 校验通过：LLM API Key 从环境变量读取，未硬编码");
    }

    private void validateKey(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "缺少 LLM API Key：" + name + " 环境变量未设置。" +
                    "请通过环境变量注入凭证，禁止写入 application.yml。");
        }
        if (isHardcoded(value)) {
            throw new IllegalStateException(
                    "LLM API Key 疑似硬编码：" + name + " 的值为占位符 '" + value + "'。" +
                    "请通过环境变量注入真实凭证，禁止在 application.yml 中写死。");
        }
    }

    // ──────────────────────────── 辅助方法 ────────────────────────────

    /** 检测 Key 值是否为硬编码占位符。 */
    public static boolean isHardcoded(String key) {
        if (key == null || key.isBlank()) return false;
        String lower = key.toLowerCase();
        for (String pattern : HARDCODED_PATTERNS) {
            if (lower.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}