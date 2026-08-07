package com.agentflow.engine.checkpoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code listByCreatedBy} 的看板列表 SQL 语义测试（#12 补 Postgres 查询）。
 *
 * <p>本机/CI 无实时 PostgreSQL，生产 Flyway 迁移（V1–V3）含 PG 专属类型（TIMESTAMPTZ/JSONB）
 * 无法跑 H2，故不构造 {@link PostgresCheckpointManager}。改为用 H2 手建一张与
 * {@code workflow_executions} 兼容的表，直接跑生产代码里 {@code listByCreatedBy} 复用的
 * {@link PostgresCheckpointManager#SELECT_EXECUTION_RECORDS}/{@code _BY_CREATOR} SQL 与
 * {@link PostgresCheckpointManager#EXECUTION_RECORD_MAPPER}（单一真相源，不复制 SQL）。
 *
 * <p>覆盖：created_by 过滤、created_at 倒序、status 归一、无匹配、空表、Null createdBy 返回全部。
 */
class PostgresCheckpointManagerTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        DataSource ds = h2DataSource();
        jdbc = new JdbcTemplate(ds);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            // DB_CLOSE_DELAY=-1 使内存库跨测试存活，需先 DROP 保证每次 setUp 干净重建
            st.execute("DROP TABLE IF EXISTS workflow_executions");
            st.execute("""
                    CREATE TABLE workflow_executions (
                        id               VARCHAR PRIMARY KEY,
                        workflow_name    VARCHAR NOT NULL,
                        workflow_version VARCHAR NOT NULL DEFAULT '1.0',
                        status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                        created_at       TIMESTAMP NOT NULL DEFAULT now(),
                        updated_at       TIMESTAMP NOT NULL DEFAULT now(),
                        created_by       VARCHAR
                    )""");
        }
    }

    @Test
    @DisplayName("按 created_by 过滤 + created_at 倒序 + status 归一")
    void listFiltersByCreatedByAndSortsDesc() {
        insert("wf-alpha", "supplier", "creator-A", "PENDING");
        insert("wf-beta", "market", "creator-A", "RUNNING");
        insert("wf-gamma", "risk", "creator-B", "SUCCESS");

        List<WorkflowExecutionRecord> mine =
                jdbc.query(PostgresCheckpointManager.SELECT_EXECUTION_RECORDS_BY_CREATOR,
                        PostgresCheckpointManager.EXECUTION_RECORD_MAPPER, "creator-A");

        // 只返回 creator-A 的两条，且按创建时间倒序（beta 后插 → 在前）
        assertThat(mine).extracting(WorkflowExecutionRecord::workflowId)
                .containsExactly("wf-beta", "wf-alpha");
        assertThat(mine).extracting(WorkflowExecutionRecord::workflowName)
                .containsExactly("market", "supplier");
        // status 归一为枚举
        assertThat(mine.getFirst().status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(mine.getLast().status()).isEqualTo(WorkflowStatus.PENDING);
        assertThat(mine).allSatisfy(r -> assertThat(r.createdAt()).isNotNull());
    }

    @Test
    @DisplayName("created_by 为空（不过滤）→ 返回全部，仍按创建时间倒序")
    void listWithoutFilterReturnsAllSortedDesc() {
        // U5 早期实例可能无 created_by
        insert("wf-a", "w1", "creator-A", "PENDING");
        insert("wf-b", "w2", null, "SUCCESS");

        List<WorkflowExecutionRecord> all =
                jdbc.query(PostgresCheckpointManager.SELECT_EXECUTION_RECORDS,
                        PostgresCheckpointManager.EXECUTION_RECORD_MAPPER);

        assertThat(all).hasSize(2);
        assertThat(all).extracting(WorkflowExecutionRecord::workflowId)
                .containsExactly("wf-b", "wf-a");
    }

    @Test
    @DisplayName("无匹配创建者 → 返回空列表")
    void listWithUnknownCreatedByReturnsEmpty() {
        insert("wf-a", "w1", "creator-A", "PENDING");

        List<WorkflowExecutionRecord> none =
                jdbc.query(PostgresCheckpointManager.SELECT_EXECUTION_RECORDS_BY_CREATOR,
                        PostgresCheckpointManager.EXECUTION_RECORD_MAPPER, "someone-else");

        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("空表 → 两种 SQL 均返回空列表")
    void listOnEmptyTableReturnsEmpty() {
        assertThat(jdbc.query(PostgresCheckpointManager.SELECT_EXECUTION_RECORDS,
                PostgresCheckpointManager.EXECUTION_RECORD_MAPPER)).isEmpty();
        assertThat(jdbc.query(PostgresCheckpointManager.SELECT_EXECUTION_RECORDS_BY_CREATOR,
                PostgresCheckpointManager.EXECUTION_RECORD_MAPPER, "creator-A")).isEmpty();
    }

    // ──────────────────────── 测试辅助 ────────────────────────

    private void insert(String id, String name, String createdBy, String status) {
        // created_at 用默认 now()，靠插入先后自然形成倒序
        jdbc.update("""
                        INSERT INTO workflow_executions (id, workflow_name, status, created_by)
                        VALUES (?, ?, ?, ?)
                        """,
                id, name, status, createdBy);
    }

    private static DataSource h2DataSource() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:checkpoint;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        return ds;
    }
}
