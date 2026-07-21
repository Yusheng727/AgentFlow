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

import javax.sql.DataSource;
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
        Integer tokens = extractTokens(output);
        String jsonOutput = toJson(output);
        Instant now = Instant.now();

        // Semaphore 限流：防 VT 并发耗尽 HikariCP
        acquireSemaphore();
        try {
            jdbc.update(
                    """
                    INSERT INTO workflow_node_outputs
                        (workflow_id, super_step, node_id, output, status, tokens_consumed, completed_at)
                    VALUES (?, ?, ?, ?::jsonb, 'COMPLETED', ?, ?)
                    ON CONFLICT (workflow_id, super_step, node_id)
                    DO UPDATE SET status = EXCLUDED.status,
                                  output = EXCLUDED.output,
                                  tokens_consumed = EXCLUDED.tokens_consumed,
                                  completed_at = EXCLUDED.completed_at
                    WHERE workflow_node_outputs.status <> 'COMPLETED'
                    """,
                    workflowId, superStep, nodeId, jsonOutput, tokens, Timestamp.from(now));
        } finally {
            writeSemaphore.release();
        }
    }

    @Override
    public void saveBarrier(String workflowId, int superStep, WorkflowContext context) {
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
                        (workflow_id, super_step, channel_values)
                    VALUES (?, ?, ?::jsonb)
                    """,
                    workflowId, superStep, jsonChannels);
        } finally {
            writeSemaphore.release();
        }
    }

    // ────────────────────────── 查询 ──────────────────────────

    @Override
    public Optional<BarrierCheckpoint> findLatestBarrier(String workflowId) {
        List<BarrierCheckpoint> results = jdbc.query(
                """
                SELECT workflow_id, super_step, channel_values, completed_at
                FROM workflow_checkpoints
                WHERE workflow_id = ?
                ORDER BY super_step DESC
                LIMIT 1
                """,
                (rs, rowNum) -> {
                    Map<String, Object> channels = fromJson(
                            rs.getString("channel_values"),
                            new TypeReference<Map<String, Object>>() {});
                    return new BarrierCheckpoint(
                            rs.getString("workflow_id"),
                            rs.getInt("super_step"),
                            channels,
                            rs.getTimestamp("completed_at").toInstant());
                },
                workflowId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public List<NodeOutputStore> findCompletedNodes(String workflowId, int superStep) {
        return jdbc.query(
                """
                SELECT workflow_id, super_step, node_id, output, status, tokens_consumed, completed_at
                FROM workflow_node_outputs
                WHERE workflow_id = ?
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
                            rs.getInt("super_step"),
                            rs.getString("node_id"),
                            output,
                            NodeStatus.valueOf(rs.getString("status")),
                            rs.getObject("tokens_consumed", Integer.class),
                            ts != null ? ts.toInstant() : null);
                },
                workflowId, superStep);
    }

    // ─────────────────── 工作流生命周期 ───────────────────

    @Override
    public void initWorkflow(String workflowId, String workflowName, String version) {
        jdbc.update(
                """
                INSERT INTO workflow_executions (id, workflow_name, workflow_version, status)
                VALUES (?, ?, ?, 'PENDING')
                ON CONFLICT (id) DO NOTHING
                """,
                workflowId, workflowName, version != null ? version : "1.0");
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