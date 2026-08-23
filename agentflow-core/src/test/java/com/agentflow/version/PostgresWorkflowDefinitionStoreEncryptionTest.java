package com.agentflow.version;

import com.agentflow.security.AesGcmColumnEncryptor;
import com.agentflow.security.ColumnEncryptor;
import com.agentflow.security.NoopColumnEncryptor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U2 R22 扩列：PostgresWorkflowDefinitionStore 条件列加密测试（H2）。
 *
 * <p>对齐 {@code PostgresCheckpointManagerEncryptionTest} 三段式：Noop 明文回归 /
 * AesGcm 密文（原始列不含明文子串——静态加密证据）+ 读还原 / legacy 明文行兼容。
 * H2 手建 TEXT 列兼容表（V8 迁移后形态）；save 的 SQL 无 PG 专有语法（ON CONFLICT
 * DO UPDATE 无 WHERE 子句，H2 PG 模式可跑——本测试用普通模式 + MERGE 不可行故直接
 * 验证：INSERT ... ON CONFLICT 在 H2 普通模式同样不支持，见 routing 注——**此处
 * save 路径改为直接验证落库形态**：以 Noop 写 + 手工断言；AesGcm 写路径的密文形态
 * 由真 PG IT（{@code PostgresWorkflowDefinitionStoreIT}）验证）。
 *
 * <p>实现注：save 的 {@code ON CONFLICT} 在 H2 跑不通，H2 侧聚焦「find/findLatest
 * 读路径解密 + Noop 直通」；写路径密文形态归真 PG IT（与 checkpoint 的 node output /
 * routing 分工一致）。
 */
class PostgresWorkflowDefinitionStoreEncryptionTest {

    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)); // 32B

    private javax.sql.DataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        ds = h2DataSource();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS workflow_definitions");
            // V8 迁移后形态：definition TEXT（密文或明文 JSON 兼容）
            st.execute("""
                    CREATE TABLE workflow_definitions (
                        workflow_name VARCHAR NOT NULL,
                        version       VARCHAR NOT NULL,
                        definition    VARCHAR,
                        created_at    TIMESTAMP NOT NULL DEFAULT now(),
                        CONSTRAINT pk_workflow_definition PRIMARY KEY (workflow_name, version)
                    )""");
        }
    }

    @Test
    @DisplayName("AesGcm：读密文行 → 解密还原 WorkflowDefinition（含 prompt_template 业务话术）")
    void aesGcmReadsEncryptedRowAndDecrypts() {
        // 用真实加密器生成密文种进表（模拟加密实例写入的行）
        AesGcmColumnEncryptor enc = new AesGcmColumnEncryptor(KEY);
        String defJson = """
                {"agentflow":{"version":"1.0"},"nodes":[{"id":"n1","agent":"a",\
                "promptTemplate":"评估 ${supplier} 的财务风险"}],"edges":[]}""";
        insert("secret-wf", "1.0", enc.encrypt(defJson));

        PostgresWorkflowDefinitionStore store = new PostgresWorkflowDefinitionStore(ds, enc);
        var def = store.find("secret-wf", "1.0").orElseThrow();
        assertThat(def.nodes().getFirst().promptTemplate()).contains("${supplier}");
    }

    @Test
    @DisplayName("legacy 明文行：AesGcm 实例读非前缀明文 JSON → 原样反序列化（升级前数据读得动）")
    void aesGcmReadsLegacyPlaintextRow() {
        String defJson = """
                {"agentflow":{"version":"1.0"},"nodes":[{"id":"n1","agent":"a",\
                "promptTemplate":"legacy prompt"}],"edges":[]}""";
        insert("legacy-wf", "1.0", defJson);

        PostgresWorkflowDefinitionStore store =
                new PostgresWorkflowDefinitionStore(ds, new AesGcmColumnEncryptor(KEY));
        assertThat(store.find("legacy-wf", "1.0").orElseThrow().nodes().getFirst().promptTemplate())
                .isEqualTo("legacy prompt");
    }

    @Test
    @DisplayName("Noop（默认）：读明文 JSON 行直通（回归，与既有行为一致）")
    void noopReadsPlaintextRow() {
        String defJson = """
                {"agentflow":{"version":"1.0"},"nodes":[{"id":"n1","agent":"a",\
                "promptTemplate":"plain prompt"}],"edges":[]}""";
        insert("plain-wf", "1.0", defJson);

        PostgresWorkflowDefinitionStore store = new PostgresWorkflowDefinitionStore(ds);
        assertThat(store.find("plain-wf", "1.0").orElseThrow().nodes().getFirst().promptTemplate())
                .isEqualTo("plain prompt");
    }

    @Test
    @DisplayName("findLatest：密文行解密还原（按 created_at 倒序取最新）")
    void findLatestDecryptsNewestRow() throws Exception {
        AesGcmColumnEncryptor enc = new AesGcmColumnEncryptor(KEY);
        insert("multi-wf", "1.0", enc.encrypt(
                "{\"agentflow\":{\"version\":\"1.0\"},\"nodes\":[{\"id\":\"old\",\"agent\":\"a\",\"promptTemplate\":\"v1\"}],\"edges\":[]}"));
        // 确保 created_at 错开（H2 now() 毫秒内碰撞会让 ORDER BY 不确定，对齐既有测试教训）
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE workflow_definitions SET created_at = DATEADD('SECOND', -10, CURRENT_TIMESTAMP) WHERE version = '1.0'");
        }
        insert("multi-wf", "2.0", enc.encrypt(
                "{\"agentflow\":{\"version\":\"1.0\"},\"nodes\":[{\"id\":\"new\",\"agent\":\"a\",\"promptTemplate\":\"v2\"}],\"edges\":[]}"));

        PostgresWorkflowDefinitionStore store = new PostgresWorkflowDefinitionStore(ds, enc);
        assertThat(store.findLatest("multi-wf").orElseThrow().nodes().getFirst().promptTemplate())
                .isEqualTo("v2");
    }

    // ──────────────────────── 测试辅助 ────────────────────────

    private void insert(String name, String version, String definition) {
        new org.springframework.jdbc.core.JdbcTemplate(ds).update(
                "INSERT INTO workflow_definitions (workflow_name, version, definition) VALUES (?, ?, ?)",
                name, version, definition);
    }

    private static DataSource h2DataSource() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:wf-def-enc;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        return ds;
    }
}
