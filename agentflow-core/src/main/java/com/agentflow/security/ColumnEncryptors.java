package com.agentflow.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ColumnEncryptor} 工厂（U7）：从环境变量 {@code AGENTFLOW_ENCRYPTION_KEY} 构造。
 *
 * <p>对齐 {@code CredentialManager} 纪律——key 只从 env 读，禁止硬编码/写进 yml。
 * <ul>
 *   <li>{@link #fromEnv()}（dev/demo 宽松）：缺 / 非法 key → {@link NoopColumnEncryptor} + warn
 *       （开发不被加密阻塞；明文落库）</li>
 *   <li>{@link #fromEnvStrict()}（生产 fail-closed）：缺 / 非法 key → 抛 {@link IllegalStateException}
 *       （敏感列必须静态加密，拒绝明文落库）</li>
 * </ul>
 */
public final class ColumnEncryptors {

    private static final Logger log = LoggerFactory.getLogger(ColumnEncryptors.class);

    /** 环境变量名（KTD-E2：从 env 读）。 */
    public static final String ENV_KEY = "AGENTFLOW_ENCRYPTION_KEY";

    private ColumnEncryptors() {
    }

    /** 宽松：缺 / 非法 key → Noop + warn（dev/demo）。启用了 key → AES-GCM。 */
    public static ColumnEncryptor fromEnv() {
        return build(System.getenv(ENV_KEY), false);
    }

    /** 严格（fail-closed，生产）：缺 / 非法 key → 抛异常，拒绝明文落库。 */
    public static ColumnEncryptor fromEnvStrict() {
        return build(System.getenv(ENV_KEY), true);
    }

    /**
     * 按给定 key 构造（宽松/严格）。包私有供测试直接测 key 处理分支（避免测试改写进程环境变量）。
     */
    static ColumnEncryptor build(String keyValue, boolean strict) {
        if (keyValue == null || keyValue.isBlank()) {
            if (strict) {
                throw new IllegalStateException(
                        "生产必须配置 " + ENV_KEY + "（base64(32B) AES-256 key）——敏感列强制静态加密，拒绝明文落库。"
                                + " 请 export " + ENV_KEY + "=... 后重启");
            }
            log.warn("未配置 {} —— 明文落库（dev/demo 宽松模式；生产请用 fromEnvStrict 或配 key）", ENV_KEY);
            return NoopColumnEncryptor.INSTANCE;
        }
        try {
            return new AesGcmColumnEncryptor(keyValue);
        } catch (IllegalArgumentException e) {
            if (strict) {
                throw new IllegalStateException(
                        ENV_KEY + " 非法（需 base64(32B) AES-256 key）: " + e.getMessage(), e);
            }
            log.warn("{} 非法（{}）——回落 Noop 明文落库（宽松模式）", ENV_KEY, e.getMessage());
            return NoopColumnEncryptor.INSTANCE;
        }
    }
}