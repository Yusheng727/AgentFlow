package com.agentflow.security;

/**
 * 列级静态加密接口（U7 R22）：checkpoint 敏感列（node output / channel / 审批载荷）的透明加解密。
 *
 * <p>引擎与 DSL 不感知加密——{@code PostgresCheckpointManager} 在序列化写库前 encrypt、读库后
 * decrypt，对上层透明（KTD-E1/E2：加密是存储层关注点）。
 */
public interface ColumnEncryptor {

    /** 加密明文 → 密文（可自描述，decrypt 能识别并还原）。 */
    String encrypt(String plaintext);

    /** 解密密文 → 明文。实现须对「非自身格式」的值原样返回（legacy 明文行兼容，KTD-E1）。 */
    String decrypt(String ciphertext);
}