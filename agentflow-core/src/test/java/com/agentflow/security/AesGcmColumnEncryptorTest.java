package com.agentflow.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U7 R22：{@link AesGcmColumnEncryptor} 测试——round-trip、随机 IV、GCM 完整性（篡改检测）、
 * legacy 明文兼容、非法 key 拒绝。
 */
class AesGcmColumnEncryptorTest {

    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)); // 32B

    @Test
    @DisplayName("round-trip：encrypt → decrypt == 原文")
    void roundTrip() {
        AesGcmColumnEncryptor enc = new AesGcmColumnEncryptor(KEY);
        String plain = "{\"agent\":\"pay\",\"amount\":1000}";
        String ct = enc.encrypt(plain);

        assertThat(ct).startsWith(AesGcmColumnEncryptor.PREFIX);
        assertThat(enc.decrypt(ct)).isEqualTo(plain);
    }

    @Test
    @DisplayName("两次 encrypt 同明文 → 密文不同（随机 IV）")
    void randomIvProducesDifferentCiphertext() {
        AesGcmColumnEncryptor enc = new AesGcmColumnEncryptor(KEY);
        String plain = "same plaintext";
        String ct1 = enc.encrypt(plain);
        String ct2 = enc.encrypt(plain);

        assertThat(ct1).isNotEqualTo(ct2);
        // 都能解回原文
        assertThat(enc.decrypt(ct1)).isEqualTo(plain);
        assertThat(enc.decrypt(ct2)).isEqualTo(plain);
    }

    @Test
    @DisplayName("篡改密文（翻 bit）→ decrypt 抛异常（GCM 完整性）")
    void tamperedCiphertextThrows() {
        AesGcmColumnEncryptor enc = new AesGcmColumnEncryptor(KEY);
        String ct = enc.encrypt("secret payload");
        // 翻转密文（ct 部分：前缀后 split ':' 取 [1]）一个字节
        String[] parts = ct.substring(AesGcmColumnEncryptor.PREFIX.length()).split(":", 2);
        byte[] body = Base64.getDecoder().decode(parts[1]);
        body[0] ^= 0x01;
        String tampered = AesGcmColumnEncryptor.PREFIX + parts[0] + ":"
                + Base64.getEncoder().encodeToString(body);

        assertThatThrownBy(() -> enc.decrypt(tampered))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("legacy 明文兼容：非 AESGCM: 前缀值 decrypt 原样返回（升级前明文行读得动）")
    void nonPrefixedLegacyPlaintextPassedThrough() {
        AesGcmColumnEncryptor enc = new AesGcmColumnEncryptor(KEY);
        String legacyJson = "{\"old\":\"plaintext row\"}";

        assertThat(enc.decrypt(legacyJson)).isEqualTo(legacyJson);
        assertThat(enc.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("非法 key：非 base64 / 非 32 字节 → 构造拒绝")
    void invalidKeyRejected() {
        assertThatThrownBy(() -> new AesGcmColumnEncryptor("not-base64!!"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AesGcmColumnEncryptor(Base64.getEncoder().encodeToString("short".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AesGcmColumnEncryptor(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("跨实例同 key 可互解（key 派生确定性，防工厂间漂移）")
    void sameKeyAcrossInstancesDecrypts() {
        AesGcmColumnEncryptor a = new AesGcmColumnEncryptor(KEY);
        AesGcmColumnEncryptor b = new AesGcmColumnEncryptor(KEY);
        String ct = a.encrypt("cross-instance");

        assertThat(b.decrypt(ct)).isEqualTo("cross-instance");
    }
}
