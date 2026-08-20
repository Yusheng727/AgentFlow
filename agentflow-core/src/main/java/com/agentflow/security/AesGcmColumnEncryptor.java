package com.agentflow.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 列加密器（U7 R22）。
 *
 * <p>格式：{@code AESGCM:<ivBase64>:<ctBase64>}——自描述（decrypt 见前缀识别），12B 随机 IV，
 * GCM 自带认证标签（detached，附加在密文尾），篡改 → decrypt 抛异常（GCM 完整性）。
 *
 * <p><b>Key 派生</b>：从 base64(32B) 显式派生成 {@link SecretKeySpec}（固定算法标识，杜绝工厂间
 * 派生漂移——review 残留点名）。每 encrypt 生成新随机 IV → 同明文两次密文不同。
 */
public final class AesGcmColumnEncryptor implements ColumnEncryptor {

    /** 自描述前缀：decrypt 对非此前缀值原样返回（legacy 明文行兼容，KTD-E1）。 */
    public static final String PREFIX = "AESGCM:";

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param keyBase64 base64 编码的 32 字节 AES-256 key（UTF-8/ASCII 皆可，按字节解码）
     */
    public AesGcmColumnEncryptor(String keyBase64) {
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new IllegalArgumentException("AES-GCM key 不能为空（base64(32B)）");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(keyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("AES-GCM key 非法 base64: " + keyBase64, e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("AES-GCM key 必须是 32 字节（256-bit），实际 " + keyBytes.length + " 字节");
        }
        this.key = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    @Override
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(iv)
                    + ":" + Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 加密失败", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith(PREFIX)) {
            // legacy 明文行：升级前落库的未加密值，原样返回（不尝试解密）
            return ciphertext;
        }
        String body = ciphertext.substring(PREFIX.length());
        String[] parts = body.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("AES-GCM 密文格式非法（期望 AESGCM:<iv>:<ct>）");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ct = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 解密失败（密文被篡改或 key 不匹配）", e);
        }
    }
}