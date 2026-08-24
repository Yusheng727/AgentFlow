package com.agentflow.version;

import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.security.AesGcmColumnEncryptor;
import com.agentflow.security.ColumnEncryptor;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgresWorkflowDefinitionStore 真 PG 集成测试（Failsafe {@code *IT}，U2 R22 扩列）。
 *
 * <p>验证 V8 迁移（definition JSONB→TEXT）后加密 key 下的完整往返：
 * save 落库为 {@code AESGCM:} 密文（原始列不含 prompt 明文——静态加密证据）、
 * find/findLatest 解密还原。CI（postgres:16 service）实跑；本地无 PG 整类跳过不红。
 */
class PostgresWorkflowDefinitionStoreIT {

    private static final String URL =
            System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/agentflow");
    private static final String USER =
            System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "agentflow");
    private static final String PASS =
            System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "agentflow");

    // 32 字节（对齐 AesGcmColumnEncryptorTest / EncryptionTest 的 KEY 先例）。
    // R22 首版误用 "it-key-"+32hex=39B 被 256-bit 校验拒绝——真 PG 实跑才暴露（H2/单元层不构造该 IT）。
    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)); // 32B

    @BeforeAll
    static void assumePostgres() {
        Assumptions.assumeTrue(pgReachable(),
                "PostgreSQL 不可达（无 SPRING_DATASOURCE_* / 本地未起 PG），跳过集成测试");
    }

    @BeforeEach
    void setUp() {
        // 先跑 Flyway 再清理：本测试类的 @BeforeEach 原先只做清理，但持久化卷可能停在旧
        // schema——构造 PostgresCheckpointManager 即幂等迁移（V3 建 workflow_definitions、
        // V8 转 TEXT），之后清理 SQL 引用的表才存在。CI 全新库恰好掩盖了这一顺序依赖。
        new com.agentflow.engine.checkpoint.PostgresCheckpointManager(
                new DriverManagerDataSource(URL, USER, PASS));
        cleanItTestData();
    }

    private void cleanItTestData() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM workflow_definitions WHERE workflow_name LIKE 'it-%'");
        } catch (Exception e) {
            throw new RuntimeException("清理集成测试数据失败", e);
        }
    }

    @Test
    @DisplayName("真 PG：加密 key 下定义落库为密文（不含 prompt 明文）+ find/findLatest 解密还原")
    void definitionCiphertextRoundTripOnRealPostgres() throws Exception {
        ColumnEncryptor enc = new AesGcmColumnEncryptor(KEY);
        PostgresWorkflowDefinitionStore store =
                new PostgresWorkflowDefinitionStore(new DriverManagerDataSource(URL, USER, PASS), enc);

        WorkflowDefinition def = new WorkflowDSLParser().parse(new java.io.ByteArrayInputStream(
                """
                agentflow:
                  version: "1.0"
                nodes:
                  - {id: n1, agent: a, prompt_template: "secret-prompt-${supplier}"}
                edges: []
                """.getBytes(StandardCharsets.UTF_8)));
        store.save("it-def-enc", "1.0", def);

        // 静态加密证据：原始列是 AESGCM: 密文、无 prompt 明文子串
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             var rs = c.createStatement().executeQuery(
                     "SELECT definition FROM workflow_definitions WHERE workflow_name = 'it-def-enc'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1))
                    .startsWith(AesGcmColumnEncryptor.PREFIX)
                    .doesNotContain("secret-prompt");
        }

        // find / findLatest 解密还原
        WorkflowDefinition back = store.find("it-def-enc", "1.0").orElseThrow();
        assertThat(back.nodes().getFirst().promptTemplate()).contains("secret-prompt-${supplier}");
        assertThat(store.findLatest("it-def-enc").orElseThrow().nodeIds()).containsExactly("n1");
    }

    private static boolean pgReachable() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }
}
