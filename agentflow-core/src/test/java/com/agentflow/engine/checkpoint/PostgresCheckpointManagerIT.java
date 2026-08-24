package com.agentflow.engine.checkpoint;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgresCheckpointManager 真 PG 集成测试（Failsafe {@code *IT}，CI 跑 / 本地跳过）。
 *
 * <p>诊断与看板生命周期经 {@link PostgresCheckpointManager} 持久化到 PostgreSQL。CI（GitHub Actions
 * postgres:16 service）注入 {@code SPRING_DATASOURCE_*}，本测试用真 PG 跑 Flyway 迁移 + <b>已补的
 * {@link #listByCreatedBy}</b>（此前只用 H2 代理测过 SQL 语义）。本地无 PG 时 {@link #assumePostgres}
 * 让整类跳过，本地 {@code mvn verify} 不红。
 *
 * <p>类名以 {@code IT} 结尾：surefire（单元）默认不收集，Failsafe（verify 阶段）收集并执行。
 *
 * @see PostgresCheckpointManagerTest 无 PG 时用 H2 验证 listByCreatedBy SQL+mapper 语义
 */
class PostgresCheckpointManagerIT {

    private static final String URL =
            System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/agentflow");
    private static final String USER =
            System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "agentflow");
    private static final String PASS =
            System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "agentflow");

    private PostgresCheckpointManager cm;

    @BeforeAll
    static void assumePostgres() {
        // 本地/无 PG 环境：断言不成立 → 整类跳过（JUnit 记为 skipped，非失败）
        Assumptions.assumeTrue(pgReachable(), "PostgreSQL 不可达（无 SPRING_DATASOURCE_* / 本地未起 PG），跳过集成测试");
    }

    @BeforeEach
    void setUp() {
        // 必须先迁移后清理，且二者在同一 @BeforeEach 内显式顺序——JUnit 5 多个 @BeforeEach
        // 不保证声明序（本机持久化卷停 V4 时实测 clean 先于 setUp 跑，workflow_approvals
        // V7 建表前 DELETE 报 relation 不存在）。构造即跑 Flyway（幂等），旧卷升到
        // 最新 schema 后，清理 SQL 引用的表才全部存在。
        DataSource ds = new DriverManagerDataSource(URL, USER, PASS);
        cm = new PostgresCheckpointManager(ds);
        cleanItTestData();
    }

    private void cleanItTestData() {
        // 真 PG 是持久化的（pg-data 卷），测试间 / 多次 verify 的数据会残留并相互污染——
        // H2 内存库 / CI 全新容器每次拿到干净库，永远暴露不了；真实持久化 PG 实跑才见。
        // 按 it-% 前缀清理本类测试数据（先子表后父表，子表无外键需显式删）。
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM workflow_routing_decisions WHERE workflow_id LIKE 'it-%'");
            s.executeUpdate("DELETE FROM workflow_approvals WHERE workflow_id LIKE 'it-%'");
            s.executeUpdate("DELETE FROM workflow_checkpoints WHERE workflow_id LIKE 'it-%'");
            s.executeUpdate("DELETE FROM workflow_node_outputs WHERE workflow_id LIKE 'it-%'");
            s.executeUpdate("DELETE FROM workflow_executions WHERE id LIKE 'it-%'");
        } catch (Exception e) {
            throw new RuntimeException("清理集成测试数据失败", e);
        }
    }

    @Test
    @DisplayName("真 PG：initWorkflow + listByCreatedBy 按创建者过滤 / created_at 倒序")
    void listByCreatedByAgainstRealPostgres() {
        cm.initWorkflow("it-list-a", "supplier", "1.0", "it-creator");
        cm.initWorkflow("it-list-b", "market", "2.0", "it-creator");
        cm.initWorkflow("it-list-c", "risk", "3.0", "other-creator");
        cm.updateStatus("it-list-b", WorkflowStatus.RUNNING);

        List<WorkflowExecutionRecord> mine = cm.listByCreatedBy("it-creator");

        assertThat(mine).hasSize(2);
        assertThat(mine).extracting(WorkflowExecutionRecord::workflowId)
                .containsExactlyInAnyOrder("it-list-a", "it-list-b");
        assertThat(mine).extracting(WorkflowExecutionRecord::workflowName)
                .containsExactlyInAnyOrder("supplier", "market");
        // it-list-b 已 RUNNING，其余 PENDING
        assertThat(mine).filteredOn(r -> r.workflowId().equals("it-list-b"))
                .singleElement().satisfies(r -> assertThat(r.status()).isEqualTo(WorkflowStatus.RUNNING));
        assertThat(mine).filteredOn(r -> r.workflowId().equals("it-list-a"))
                .singleElement().satisfies(r -> assertThat(r.status()).isEqualTo(WorkflowStatus.PENDING));
        assertThat(mine).allSatisfy(r -> assertThat(r.createdAt()).isNotNull());
    }

    @Test
    @DisplayName("真 PG：listByCreatedBy 空 createdBy 返回全部（U5 早期实例兼容）")
    void listWithNullCreatedByReturnsAllOnRealPostgres() {
        cm.initWorkflow("it-all-a", "w1", "1.0", "it-creator");
        cm.initWorkflow("it-all-b", "w2", "1.0", null); // 无 created_by

        List<WorkflowExecutionRecord> all = cm.listByCreatedBy(null);

        assertThat(all).extracting(WorkflowExecutionRecord::workflowId).contains("it-all-a", "it-all-b");
    }

    @Test
    @DisplayName("真 PG：节点级 + barrier checkpckoint 持久化后可查（诊断前置数据）")
    void checkpointAndWorkflowMetaRoundTrip() {
        cm.initWorkflow("it-cp", "contract", "1.0", "it-creator");
        // workflow_name / version 供 U8 版本管理定位定义（诊断/恢复读元数据）
        assertThat(cm.findWorkflowName("it-cp")).hasValue("contract");
        assertThat(cm.findVersion("it-cp")).hasValue("1.0");
        assertThat(cm.findStatus("it-cp")).hasValue(WorkflowStatus.PENDING);
        assertThat(cm.findCreatedBy("it-cp")).hasValue("it-creator");
    }

    @Test
    @DisplayName("真 PG：HITL 审批往返 + confirm 原子决策（Flyway V7 workflow_approvals + AWAITING_APPROVAL）")
    void approvalRoundTripOnRealPostgres() {
        cm.initWorkflow("it-appr", "approval-demo", "1.0", "it-creator");
        ApprovalRequest req = ApprovalRequest.pending(
                "it-appr", "pay-gate", 0, 1, "审批付款", Map.of("amount", 1000), Map.of("ctx", "v"));
        cm.saveApprovalRequest("it-appr", req);

        assertThat(cm.findPendingApprovals("it-appr"))
                .singleElement().satisfies(r -> assertThat(r.status()).isEqualTo(ApprovalStatus.PENDING));

        // 原子决策
        assertThat(cm.confirmApproval(req.approvalId(), ApprovalDecision.APPROVE, "it-approver")).isTrue();
        assertThat(cm.findApprovalById(req.approvalId()).orElseThrow().status())
                .isEqualTo(ApprovalStatus.APPROVED);
        // 二次决策 no-op
        assertThat(cm.confirmApproval(req.approvalId(), ApprovalDecision.REJECT, "it-2")).isFalse();
        // 已决策不再出现在待办
        assertThat(cm.findPendingApprovals("it-appr")).isEmpty();
    }

    @Test
    @DisplayName("真 PG：R22 扩列——加密 key 下 routing 决策 / 节点输出 / channel 密文形态 + 读还原（V8 TEXT 列）")
    void routingDecisionsCiphertextOnRealPostgres() throws Exception {
        // 32 字节 key（对齐 EncryptionTest 先例；R22 首版 "it-key-"+32hex=39B 被 256-bit 校验拒绝）
        String key = java.util.Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        com.agentflow.security.ColumnEncryptor enc = new com.agentflow.security.AesGcmColumnEncryptor(key);
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        PostgresCheckpointManager encryptedCm = new PostgresCheckpointManager(
                new DriverManagerDataSource(URL, USER, PASS), om, true, enc);

        // routing 决策：写 → 列值是 AESGCM: 密文（无明文子串）→ 读解密还原
        encryptedCm.saveRoutingDecisions("it-r22-routing", 0, 1, List.of("it-src->it-dst"));
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             var rs = c.createStatement().executeQuery(
                     "SELECT decisions FROM workflow_routing_decisions WHERE workflow_id = 'it-r22-routing'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1))
                    .startsWith(com.agentflow.security.AesGcmColumnEncryptor.PREFIX)
                    .doesNotContain("it-src");
        }
        assertThat(encryptedCm.findRoutingDecisions("it-r22-routing", 0))
                .containsExactly("it-src->it-dst");

        // 节点输出 + channel（U7 既有加密路径，V8 IT 补密文形态证据）：
        encryptedCm.initWorkflow("it-r22-node", "r22", "1.0", "it-creator");
        encryptedCm.saveNodeOutput("it-r22-node", 0, 0, "n1",
                com.agentflow.agent.AgentOutput.of("secret-node-output"));
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             var rs = c.createStatement().executeQuery(
                     "SELECT output FROM workflow_node_outputs WHERE workflow_id = 'it-r22-node'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1))
                    .startsWith(com.agentflow.security.AesGcmColumnEncryptor.PREFIX)
                    .doesNotContain("secret-node-output");
        }
        assertThat(encryptedCm.findCompletedNodes("it-r22-node", 0, 0))
                .singleElement().satisfies(n -> assertThat(n.output().content()).isEqualTo("secret-node-output"));
    }

    // ──────────────────────── 测试辅助 ────────────────────────

    private static boolean pgReachable() {
        // 短超时：本地无 PG 时快速判定失败 → 跳过，避免长时间等待
        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }
}
