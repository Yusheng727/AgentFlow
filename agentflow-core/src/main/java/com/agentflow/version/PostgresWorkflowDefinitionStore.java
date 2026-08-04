package com.agentflow.version;

import com.agentflow.dsl.WorkflowDefinition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Optional;

/**
 * PostgreSQL 版 {@link WorkflowDefinitionStore}（U8，R14）。
 *
 * <p>把解析后的 {@link WorkflowDefinition} 序列化为 JSONB 存入
 * {@code workflow_definitions(workflow_name, version, definition)} 表；恢复/retry 按
 * checkpoint 的 name+version 反序列化取回。JSON 序列化用 camelCase 分量名（与 DSL POJO 一致、
 * 自洽往返），与 YAML 解析的 SNAKE_CASE 无关。
 *
 * <p>依赖 Flyway 在启动时执行 {@code V3__workflow_definitions.sql}（与 PostgresCheckpointManager 同一机制）。
 */
public final class PostgresWorkflowDefinitionStore implements WorkflowDefinitionStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper jsonMapper;

    public PostgresWorkflowDefinitionStore(DataSource dataSource) {
        this(dataSource, defaultJsonMapper());
    }

    public PostgresWorkflowDefinitionStore(DataSource dataSource, ObjectMapper jsonMapper) {
        this(new JdbcTemplate(dataSource), jsonMapper);
    }

    /** 测试注入：直接用 JdbcTemplate（mock/嵌入式），便于覆盖 SQL 分支。 */
    PostgresWorkflowDefinitionStore(JdbcTemplate jdbc, ObjectMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper == null ? defaultJsonMapper() : jsonMapper;
    }

    @Override
    public void save(String workflowName, String version, WorkflowDefinition definition) {
        jdbc.update(
                "INSERT INTO workflow_definitions (workflow_name, version, definition) VALUES (?, ?, ?::jsonb) "
                        + "ON CONFLICT (workflow_name, version) DO UPDATE SET definition = EXCLUDED.definition",
                workflowName, version, toJson(definition));
    }

    @Override
    public Optional<WorkflowDefinition> find(String workflowName, String version) {
        return Optional.ofNullable(jdbc.query(
                "SELECT definition FROM workflow_definitions WHERE workflow_name = ? AND version = ?",
                rs -> rs.next() ? fromJson(rs.getString("definition")) : null,
                workflowName, version));
    }

    @Override
    public Optional<WorkflowDefinition> findLatest(String workflowName) {
        return Optional.ofNullable(jdbc.query(
                "SELECT definition FROM workflow_definitions WHERE workflow_name = ? ORDER BY created_at DESC LIMIT 1",
                rs -> rs.next() ? fromJson(rs.getString("definition")) : null,
                workflowName));
    }

    /** 序列化定义（camelCase 分量名，自洽往返）。 */
    String toJson(WorkflowDefinition def) {
        try {
            return jsonMapper.writeValueAsString(def);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 WorkflowDefinition 失败", e);
        }
    }

    WorkflowDefinition fromJson(String json) {
        try {
            return jsonMapper.readValue(json, WorkflowDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化 WorkflowDefinition 失败", e);
        }
    }

    static ObjectMapper defaultJsonMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }
}
