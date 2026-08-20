package com.agentflow.engine.checkpoint;

import com.agentflow.security.AesGcmColumnEncryptor;
import com.agentflow.security.ColumnEncryptor;
import com.agentflow.security.NoopColumnEncryptor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U7 R22：PostgresCheckpointManager 条件列加密测试（H2）。
 *
 * <p>用 {@code skipMigrations} 构造 seam + H2 手建 {@code workflow_approvals} 兼容表（无 PG 专有
 * SQL），驱动真实实例的 {@code saveApprovalRequest}/`findApprovalById`（审批载荷 request_payload /
 * context_snapshot 走同一 {@code toEncryptedJson}/{@code decryptRaw} 加密路径，且该 SQL 无
 * {@code ON CONFLICT...WHERE} 可在 H2 跑）。验证：Noop 明文 JSON 落 TEXT 列（回归）；AesGcm 落密文
 * （原始列<b>不含明文子串</b>——静态加密证据）、读取解密还原。
 *
 * <p>node output / channel 的加解密走相同 {@code toEncryptedJson}/{@code decryptRaw}，但其
 * INSERT 含 PG 专有 {@code ON CONFLICT...WHERE} 在 H2 跑不通——密文形态由真 PG IT
 * （{@link PostgresCheckpointManagerIT}，Flyway V1–V7）覆盖，本类聚焦「同一加密路径」分支。
 */
class PostgresCheckpointManagerEncryptionTest {

    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)); // 32B

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        DataSource ds = h2DataSource();
        jdbc = new JdbcTemplate(ds);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS workflow_approvals");
            st.execute("""
                    CREATE TABLE workflow_approvals (
                        approval_id      VARCHAR PRIMARY KEY,
                        workflow_id      VARCHAR NOT NULL,
                        node_id          VARCHAR NOT NULL,
                        round            INT NOT NULL DEFAULT 0,
                        super_step       INT NOT NULL,
                        description      VARCHAR,
                        request_payload  VARCHAR,
                        context_snapshot VARCHAR,
                        status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                        decided_by       VARCHAR,
                        created_at       TIMESTAMP NOT NULL DEFAULT now(),
                        decided_at       TIMESTAMP
                    )""");
        }
    }

    private PostgresCheckpointManager manager(ColumnEncryptor enc) {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new PostgresCheckpointManager(h2DataSource(), om, false, enc);
    }

    private static ApprovalRequest pending(String flow, String payload, String snapshot) {
        return ApprovalRequest.pending(flow, "gate", 0, 1, "pay",
                Map.of("amount", payload), Map.of("ctx", snapshot));
    }

    private String rawPayload(String workflowId) {
        return jdbc.queryForObject(
                "SELECT request_payload FROM workflow_approvals WHERE workflow_id = ?",
                String.class, workflowId);
    }

    @Test
    @DisplayName("Noop（默认）：审批载荷明文 JSON 落 TEXT 列（回归，与 U3 一致）")
    void noopStoresPlaintext() {
        PostgresCheckpointManager cm = manager(NoopColumnEncryptor.INSTANCE);
        cm.saveApprovalRequest("wf1", pending("wf1", "secret-amount-1000", "ctx-val"));

        assertThat(rawPayload("wf1"))
                .contains("secret-amount-1000")
                .doesNotContain(AesGcmColumnEncryptor.PREFIX);
        ApprovalRequest read = cm.findApprovalById(
                cm.findPendingApprovals("wf1").getFirst().approvalId()).orElseThrow();
        assertThat(read.requestPayload()).containsEntry("amount", "secret-amount-1000");
    }

    @Test
    @DisplayName("AesGcm：落库为密文（原始列不含明文子串），读取解密还原")
    void aesGcmStoresCiphertextAndReadsBack() {
        ColumnEncryptor enc = new AesGcmColumnEncryptor(KEY);
        PostgresCheckpointManager cm = manager(enc);
        String secret = "sensitive-approval-payload";
        cm.saveApprovalRequest("wf2", pending("wf2", secret, "snap-val"));

        // 静态加密证据：TEXT 列无明文子串 + 加密前缀
        String stored = rawPayload("wf2");
        assertThat(stored).startsWith(AesGcmColumnEncryptor.PREFIX).doesNotContain(secret);
        // 读取解密还原
        ApprovalRequest read = cm.findApprovalById(
                cm.findPendingApprovals("wf2").getFirst().approvalId()).orElseThrow();
        assertThat(read.requestPayload()).containsEntry("amount", secret);
    }

    @Test
    @DisplayName("legacy 明文行兼容：AesGcm 读非前缀明文 → 原样返回（升级前数据读得动）")
    void aesGcmReadsLegacyPlaintext() {
        // 先 Noop 写明文（模拟升级前），再用 AesGcm 实例读 → 不碎裂
        PostgresCheckpointManager noopCm = manager(NoopColumnEncryptor.INSTANCE);
        noopCm.saveApprovalRequest("wf3", pending("wf3", "legacy-amount", "snap"));
        String approvalId = noopCm.findPendingApprovals("wf3").getFirst().approvalId();

        PostgresCheckpointManager aesCm = manager(new AesGcmColumnEncryptor(KEY));
        ApprovalRequest read = aesCm.findApprovalById(approvalId).orElseThrow();
        assertThat(read.requestPayload()).containsEntry("amount", "legacy-amount");
        assertThat(read.contextSnapshot()).containsEntry("ctx", "snap");
    }

    private static DataSource h2DataSource() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:encryption;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        return ds;
    }
}
