package com.agentflow.engine.checkpoint;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgresCheckpointManager 审批方法（U3）H2 测试。
 *
 * <p>生产 Flyway（V1–V7）含 PG 专属类型（TIMESTAMPTZ/JSONB）在 H2 跑不通，本类用
 * {@code skipMigrations} 构造 seam 手建兼容 {@code workflow_approvals} 表，驱动真实实例方法
 * （save/findPending/findById/confirm + JSON 往返），既验证 SQL+序列化语义，也覆盖审批方法行进
 * JaCoCo（无 PG 本地也能绿）。真 PG（Flyway V7 validated）用 {@link PostgresCheckpointManagerIT}。
 */
class PostgresCheckpointManagerApprovalTest {

    private PostgresCheckpointManager cm;
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
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        cm = new PostgresCheckpointManager(ds, om, false);
    }

    private ApprovalRequest pending(String flow, String node, int step) {
        return ApprovalRequest.pending(flow, node, 0, step, "审批付款",
                Map.of("amount", 1000), Map.of("ctx-key", "val"));
    }

    @Test
    @DisplayName("save + findPending + findById 往返，快照/载荷 JSON 保真")
    void roundTrip() {
        ApprovalRequest r = pending("wf1", "pay-gate", 2);
        cm.saveApprovalRequest(r.workflowId(), r);

        List<ApprovalRequest> pendingList = cm.findPendingApprovals("wf1");
        assertThat(pendingList).hasSize(1);
        assertThat(pendingList.getFirst().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(pendingList.getFirst().description()).isEqualTo("审批付款");

        ApprovalRequest byId = cm.findApprovalById(r.approvalId()).orElseThrow();
        assertThat(byId.nodeId()).isEqualTo("pay-gate");
        assertThat(byId.superStep()).isEqualTo(2);
        assertThat(byId.contextSnapshot()).containsEntry("ctx-key", "val");
        assertThat(byId.requestPayload()).containsEntry("amount", 1000);
    }

    @Test
    @DisplayName("confirm APPROVE → APPROVED + decidedBy；重复 confirm no-op")
    void confirmApprove() {
        ApprovalRequest r = pending("wfA", "n", 0);
        String id = cm.saveApprovalRequest("wfA", r);

        assertThat(cm.confirmApproval(id, ApprovalDecision.APPROVE, "caller-1")).isTrue();
        assertThat(cm.findApprovalById(id).orElseThrow().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(cm.findApprovalById(id).orElseThrow().decidedBy()).isEqualTo("caller-1");
        assertThat(cm.confirmApproval(id, ApprovalDecision.APPROVE, "caller-2")).isFalse(); // 已决策
        assertThat(cm.findPendingApprovals("wfA")).isEmpty();
    }

    @Test
    @DisplayName("confirm REJECT → REJECTED")
    void confirmReject() {
        ApprovalRequest r = pending("wfR", "n", 0);
        String id = cm.saveApprovalRequest("wfR", r);
        assertThat(cm.confirmApproval(id, ApprovalDecision.REJECT, "caller")).isTrue();
        assertThat(cm.findApprovalById(id).orElseThrow().status()).isEqualTo(ApprovalStatus.REJECTED);
    }

    @Test
    @DisplayName("未知 id / 未知 flow → empty / confirm false")
    void unknownId() {
        assertThat(cm.findApprovalById("nope")).isEmpty();
        assertThat(cm.confirmApproval("nope", ApprovalDecision.APPROVE, "c")).isFalse();
        assertThat(cm.findPendingApprovals("nope-flow")).isEmpty();
    }

    private static DataSource h2DataSource() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:approval;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        return ds;
    }
}