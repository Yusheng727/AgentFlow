package com.agentflow.security;

/**
 * 恒等加密器（U7）：dev/demo 未配置 key 时的默认实现，原样透传不加密。
 *
 * <p>通过 {@link ColumnEncryptors#fromEnv()}（宽松）在缺/非法 key 时返回，保证开发/测试不被
 * 加密阻塞；生产经 {@link ColumnEncryptors#fromEnvStrict()}（fail-closed）必须真加密。
 */
public record NoopColumnEncryptor() implements ColumnEncryptor {

    @Override
    public String encrypt(String plaintext) {
        return plaintext;
    }

    @Override
    public String decrypt(String ciphertext) {
        return ciphertext;
    }

    /** 单例（无状态，避免重复分配）。 */
    public static final NoopColumnEncryptor INSTANCE = new NoopColumnEncryptor();
}