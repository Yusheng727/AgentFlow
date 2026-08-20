package com.agentflow.engine.checkpoint;

import com.agentflow.agent.AgentOutput;
import com.agentflow.engine.ChannelValue;
import com.agentflow.engine.WorkflowContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * PostgreSQL CheckpointManager（生产实现）。
 *
 * <p>使用 JDBC（JdbcTemplate）持久化 checkpoint 数据到 PostgreSQL。
 * 构造时自动运行 Flyway 迁移（{@code db/migration/V1__checkpoint_schema.sql}）。
 *
 * <h3>并发控制</h3>
 * <ul>
 *   <li>{@link Semaphore Semaphore(20)} 限制并发 DB 写入数 ≤ HikariCP 连接池大小，
 *       防止 Virtual Thread 并发 50+ 耗尽连接池</li>
 *   <li>幂等写入通过 {@code ON CONFLICT ... DO UPDATE ... WHERE status <> 'COMPLETED'} 保证：
 *       COMPLETED 终态不可覆盖，FAILED/IN_PROGRESS 可升级为 COMPLETED</li>
 * </ul>
 *
 * <h3>JSONB 序列化</h3>
 * <p>AgentOutput 和 ChannelValues 通过 Jackson ObjectMapper（SNAKE_CASE + JavaTimeModule）
 * 序列化为 JSONB，查询时反序列化回 Java 对象。
 *
 * @see InMemoryCheckpointManager 开发测试替代实现
 */
public final class PostgresCheckpointManager implements CheckpointManager {

    private static final Logger log = LoggerFactory.getLogger(PostgresCheckpointManager.class);
    private static final int MAX_CONCURRENT_WRITES = 20;

    private final JdbcTemplate jdbc;
    private final ObjectMapper jsonMapper;
    private final Semaphore writeSemaphore = new Semaphore(MAX_CONCURRENT_WRITES);

    /**
     * 看板列表端点（#12）的 {@code workflow_executions} 查询 SQL + 行映射。
     *
     * <p>SQL 与 RowMapper 均为 package-private static（单一真相源）：{@code listByCreatedBy} 直接复用，
     * 集成测试 ({@code PostgresCheckpointManagerTest}) 用 H2 建兼容表跑同一 SQL + 映射验证 WHERE/ORDER BY 语义，
     * 不在测试里复制 SQL 导致漂移。
     */
    static final String SELECT_EXECUTION_RECORDS =
            """
            SELECT id, workflow_name, status, created_at
            FROM workflow_executions
            ORDER BY created_at DESC
            """;

    static final String SELECT_EXECUTION_RECORDS_BY_CREATOR =
            """
            SELECT id, workflow_name, status, created_at
            FROM workflow_executions
            WHERE created_by = ?
            ORDER BY created_at DESC
            """;

    static final RowMapper<WorkflowExecutionRecord> EXECUTION_RECORD_MAPPER =
            (ResultSet rs, int rowNum) -> {
                Timestamp ts = rs.getTimestamp("created_at");
                return new WorkflowExecutionRecord(
                        rs.getString("id"),
                        rs.getString("workflow_name"),
                        WorkflowStatus.valueOf(rs.getString("status")),
                        ts != null ? ts.toInstant() : null);
            };

    /**
     * 创建 PostgresCheckpointManager 并运行 Flyway 迁移。
     *
     * @param dataSource PostgreSQL DataSource（需已配置 HikariCP，max pool size ≥ 20）
     */
    public PostgresCheckpointManager(DataSource dataSource) {
        this(dataSource, defaultJsonMapper());
    }

    /**
     * 创建 PostgresCheckpointManager（指定 ObjectMapper）。
     */
    public PostgresCheckpointManager(DataSource dataSource, ObjectMapper jsonMapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jsonMapper = jsonMapper;

        // 运行 Flyway 迁移（幂等：仅执行待迁移的版本）
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        int applied = flyway.migrate().migrationsExecuted;
        if (applied > 0) {
            log.info("Flyway 迁移完成，执行 {} 个迁移", applied);
        }
    }

    // ──────────────────────────── 写入 ────────────────────────────

    @Override
    public void saveNodeOutput(String workflowId, int superStep, String nodeId, AgentOutput output) {
        saveNodeOutput(workflowId, 0, superStep, nodeId, output);
    }

    @Override
    public void saveNodeOutput(String workflowId, int round, int superStep, String nodeId, AgentOutput output) {
        Integer tokens = extractTokens(output);
        String jsonOutput = toJson(output);
        Instant now = Instant.now();

        // Semaphore 限流：防 VT 并发耗尽 HikariCP
        acquireSemaphore();
        try {
            jdbc.update(
                    """
                    INSERT INTO workflow_node_outputs
                        (workflow_id, round, super_step, node_id, output, status, tokens_consumed, completed_at)
                    VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?)
                    ON CONFLICT (workflow_id, round, super_step, node_id)
                    DO UPDATE SET status = EXCLUDED.status,
                                  output = EXCLUDED.output,
                                  tokens_consumed = EXCLUDED.tokens_consumed,
                                  completed_at = EXCLUDED.completed_at
                    WHERE workflow_node_outputs.status <> 'COMPLETED'
                    """,
                    workflowId, round, superStep, nodeId, jsonOutput, tokens, Timestamp.from(now));
        } finally {
            writeSemaphore.release();
        }
    }

    @Override
    public void saveBarrier(String workflowId, int superStep, WorkflowContext context) {
        saveBarrier(workflowId, 0, superStep, context);
    }

    @Override
    public void saveBarrier(String workflowId, int round, int superStep, WorkflowContext context) {
        // 从 WorkflowContext 提取 channel 原始值（ChannelValue → value）
        Map<String, Object> channelValues = context.values().entrySet().stream()
                .filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().value()));
        String jsonChannels = toJson(channelValues);

        acquireSemaphore();
        try {
            jdbc.update(
                    """
                    INSERT INTO workflow_checkpoints
                        (workflow_id, round, super_step, channel_values)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (workflow_id, round, super_step) DO NOTHING
                    """,
                    workflowId, round, superStep, jsonChannels);
        } finally {
            writeSemaphore.release();
        }
    }

    @Override
    public void saveRoutingDecisions(String workflowId, int superStep, List<String> decisions) {
        saveRoutingDecisions(workflowId, 0, superStep, decisions);
    }

    @Override
    public void saveRoutingDecisions(String workflowId, int round, int superStep, List<String> decisions) {
        String json = toJson(decisions);
        acquireSemaphore();
        try {
            jdbc.update(
                    """
                    INSERT INTO workflow_routing_decisions (workflow_id, round, super_step, decisions)
                    VALUES (?, ?, ?, ?::jsonb)
                    ON CONFLICT (workflow_id, round)
                    DO UPDATE SET super_step = EXCLUDED.super_step,
                                  decisions = EXCLUDED.decisions,
                                  updated_at = now()
                    """,
                    workflowId, round, superStep, json);
        } finally {
            writeSemaphore.release();
        }
    }

    // ────────────────────────── 查询 ──────────────────────────

    @Override
    public Optional<BarrierCheckpoint> findLatestBarrier(String workflowId) {
        List<BarrierCheckpoint> results = jdbc.query(
                """
                SELECT workflow_id, round, super_step, channel_values, completed_at
                FROM workflow_checkpoints
                WHERE workflow_id = ?
                ORDER BY round DESC, super_step DESC
                LIMIT 1
                """,
                (rs, rowNum) -> {
                    Map<String, Object> channels = fromJson(
                            rs.getString("channel_values"),
                            new TypeReference<Map<String, Object>>() {});
                    return new BarrierCheckpoint(
                            rs.getString("workflow_id"),
                            rs.getInt("round"),
                            rs.getInt("super_step"),
                            channels,
                            rs.getTimestamp("completed_at").toInstant());
                },
                workflowId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public List<NodeOutputStore> findCompletedNodes(String workflowId, int superStep) {
        return findCompletedNodes(workflowId, 0, superStep);
    }

    @Override
    public List<NodeOutputStore> findCompletedNodes(String workflowId, int round, int superStep) {
        return jdbc.query(
                """
                SELECT workflow_id, round, super_step, node_id, output, status, tokens_consumed, completed_at
                FROM workflow_node_outputs
                WHERE workflow_id = ?
                  AND round = ?
                  AND super_step = ?
                  AND status = 'COMPLETED'
                  AND output IS NOT NULL
                """,
                (rs, rowNum) -> {
                    AgentOutput output = fromJson(
                            rs.getString("output"), AgentOutput.class);
                    Timestamp ts = rs.getTimestamp("completed_at");
                    return new NodeOutputStore(
                            rs.getString("workflow_id"),
                            rs.getInt("round"),
                            rs.getInt("super_step"),
                            rs.getString("node_id"),
                            output,
                            NodeStatus.valueOf(rs.getString("status")),
                            rs.getObject("tokens_consumed", Integer.class),
                            ts != null ? ts.toInstant() : null);
                },
                workflowId, round, superStep);
    }

    @Override
    public List<String> findRoutingDecisions(String workflowId) {
        return findRoutingDecisions(workflowId, 0);
    }

    @Override
    public List<String> findRoutingDecisions(String workflowId, int round) {
        List<String> rows = jdbc.query(
                """
                SELECT decisions FROM workflow_routing_decisions WHERE workflow_id = ? AND round = ?
                """,
                (rs, rowNum) -> rs.getString("decisions"),
                workflowId, round);
        if (rows.isEmpty() || rows.getFirst() == null) {
            return List.of();
        }
        return fromJson(rows.getFirst(), new TypeReference<List<String>>() {});
    }

    // ──────────────────── HITL 审批（U1） ────────────────────

    @Override
    public String saveApprovalRequest(String workflowId, ApprovalRequest request) {
        acquireSemaphore();
        try {
            jdbc.update(INSERT_APPROVAL_SQL,
                    request.approvalId(), workflowId, request.nodeId(), request.round(), request.superStep(),
                    request.description(),
                    toJson(request.requestPayload()),
                    toJson(request.contextSnapshot()),
                    Timestamp.from(request.createdAt() != null ? request.createdAt() : Instant.now()));
        } finally {
            writeSemaphore.release();
        }
        return request.approvalId();
    }

    @Override
    public List<ApprovalRequest> findPendingApprovals(String workflowId) {
        return jdbc.query(SELECT_PENDING_APPROVALS_SQL, this::approvalRowMapper, workflowId);
    }

    @Override
    public Optional<ApprovalRequest> findApprovalById(String approvalId) {
        List<ApprovalRequest> rows = jdbc.query(SELECT_APPROVAL_BY_ID_SQL, this::approvalRowMapper, approvalId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public boolean confirmApproval(String approvalId, ApprovalDecision decision, String decidedBy) {
        String status = decision == ApprovalDecision.APPROVE ? "APPROVED" : "REJECTED";
        return jdbc.update(UPDATE_APPROVAL_SQL, status, decidedBy, approvalId) == 1;
    }

    private ApprovalRequest approvalRowMapper(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> payload = fromJson(
                rs.getString("request_payload"), new TypeReference<Map<String, Object>>() {});
        Map<String, Object> snapshot = fromJson(
                rs.getString("context_snapshot"), new TypeReference<Map<String, Object>>() {});
        Timestamp ts = rs.getTimestamp("created_at");
        return new ApprovalRequest(
                rs.getString("approval_id"),
                rs.getString("workflow_id"),
                rs.getString("node_id"),
                rs.getInt("round"),
                rs.getInt("super_step"),
                rs.getString("description"),
                payload,
                snapshot,
                ApprovalStatus.valueOf(rs.getString("status")),
                rs.getString("decided_by"),
                ts != null ? ts.toInstant() : null);
    }

    // ─────────────────── 工作流生命周期 ───────────────────

    @Override
    public void initWorkflow(String workflowId, String workflowName, String version, String createdBy) {
        jdbc.update(
                """
                INSERT INTO workflow_executions (id, workflow_name, workflow_version, status, created_by)
                VALUES (?, ?, ?, 'PENDING', ?)
                ON CONFLICT (id) DO NOTHING
                """,
                workflowId, workflowName, version != null ? version : "1.0", createdBy);
    }

    @Override
    public void updateStatus(String workflowId, WorkflowStatus status) {
        jdbc.update(
                """
                UPDATE workflow_executions
                SET status = ?, updated_at = now()
                WHERE id = ?
                """,
                status.name(), workflowId);
    }

    @Override
    public boolean tryClaim(String workflowId) {
        // 单条条件 UPDATE：仅 PENDING→RUNNING 命中，返回影响行数判定是否取得执行权（原子，防并发双跑）
        return jdbc.update(TRY_CLAIM_SQL, workflowId) == 1;
    }

    /** tryClaim 的条件转移 SQL（package-private 单一真相源，PostgresCheckpointManagerTest 用 H2 兼容表断言）。 */
    static final String TRY_CLAIM_SQL =
            """
            UPDATE workflow_executions
            SET status = 'RUNNING', updated_at = now()
            WHERE id = ? AND status = 'PENDING'
            """;

    // ──────────────────── HITL 审批 SQL（U1，单一真相源） ────────────────────

    static final String INSERT_APPROVAL_SQL =
            """
            INSERT INTO workflow_approvals
                (approval_id, workflow_id, node_id, round, super_step, description,
                 request_payload, context_snapshot, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
            ON CONFLICT (approval_id) DO NOTHING
            """;

    static final String SELECT_PENDING_APPROVALS_SQL =
            """
            SELECT approval_id, workflow_id, node_id, round, super_step, description,
                   request_payload, context_snapshot, status, decided_by, created_at
            FROM workflow_approvals
            WHERE workflow_id = ? AND status = 'PENDING'
            ORDER BY created_at
            """;

    static final String SELECT_APPROVAL_BY_ID_SQL =
            """
            SELECT approval_id, workflow_id, node_id, round, super_step, description,
                   request_payload, context_snapshot, status, decided_by, created_at
            FROM workflow_approvals
            WHERE approval_id = ?
            """;

    static final String UPDATE_APPROVAL_SQL =
            """
            UPDATE workflow_approvals
            SET status = ?, decided_by = ?, decided_at = now()
            WHERE approval_id = ? AND status = 'PENDING'
            """;

    @Override
    public Optional<WorkflowStatus> findStatus(String workflowId) {
        List<WorkflowStatus> results = jdbc.query(
                """
                SELECT status FROM workflow_executions WHERE id = ?
                """,
                (rs, rowNum) -> WorkflowStatus.valueOf(rs.getString("status")),
                workflowId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public Optional<String> findCreatedBy(String workflowId) {
        List<String> results = jdbc.query(
                """
                SELECT created_by FROM workflow_executions WHERE id = ?
                """,
                (rs, rowNum) -> rs.getString("created_by"),
                workflowId);
        return results.isEmpty() || results.getFirst() == null
                ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public Optional<String> findWorkflowName(String workflowId) {
        List<String> results = jdbc.query(
                """
                SELECT workflow_name FROM workflow_executions WHERE id = ?
                """,
                (rs, rowNum) -> rs.getString("workflow_name"),
                workflowId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public Optional<String> findVersion(String workflowId) {
        List<String> results = jdbc.query(
                """
                SELECT workflow_version FROM workflow_executions WHERE id = ?
                """,
                (rs, rowNum) -> rs.getString("workflow_version"),
                workflowId);
        return results.isEmpty() || results.getFirst() == null
                ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public List<WorkflowExecutionRecord> listByCreatedBy(String createdBy) {
        // 与 InMemoryCheckpointManager 语义一致：createdBy 为空则返回全部（兼容 U5 早期未设 created_by 的实例），
        // 否则仅返回该创建者的记录，均按创建时间倒序（看板「最近执行」就近优先）。
        // workflow_name 在 schema 中 NOT NULL，无需像 InMemory 那样回退到 workflowId。
        // 已有 idx_workflow_created_by 索引支撑 created_by 过滤。
        if (createdBy == null) {
            return jdbc.query(SELECT_EXECUTION_RECORDS, EXECUTION_RECORD_MAPPER);
        }
        return jdbc.query(SELECT_EXECUTION_RECORDS_BY_CREATOR, EXECUTION_RECORD_MAPPER, createdBy);
    }

    // ──────────────────────── 辅助方法 ────────────────────────

    private void acquireSemaphore() {
        try {
            writeSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for checkpoint write permit", e);
        }
    }

    private String toJson(Object obj) {
        try {
            return jsonMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize checkpoint data to JSON", e);
        }
    }

    private <T> T fromJson(String json, Class<T> clazz) {
        if (json == null) return null;
        try {
            return jsonMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize checkpoint data from JSON: " + clazz.getSimpleName(), e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null) return null;
        try {
            return jsonMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize checkpoint data from JSON: " + typeRef.getType(), e);
        }
    }

    private static Integer extractTokens(AgentOutput output) {
        if (output == null || output.metadata() == null) {
            return null;
        }
        Object usage = output.metadata().get("usage");
        if (usage instanceof Map<?, ?> usageMap) {
            Object total = usageMap.get("totalTokens");
            if (total instanceof Number n) {
                return n.intValue();
            }
        }
        return null;
    }

    private static ObjectMapper defaultJsonMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}