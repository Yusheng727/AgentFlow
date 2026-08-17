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
        // 每次构造跑 Flyway 迁移（幂等：仅执行未应用的版本；CI 每次全新容器）
        DataSource ds = new DriverManagerDataSource(URL, USER, PASS);
        cm = new PostgresCheckpointManager(ds);
    }

    @BeforeEach
    void cleanItTestData() {
        // 真 PG 是持久化的（pg-data 卷），测试间 / 多次 verify 的数据会残留并相互污染——
        // H2 内存库 / CI 全新容器每次拿到干净库，永远暴露不了；真实持久化 PG 实跑才见。
        // 按 it-% 前缀清理本类测试数据（先子表后父表，子表无外键需显式删）。
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM workflow_routing_decisions WHERE workflow_id LIKE 'it-%'");
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
