package com.agentflow.version;

import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.security.ColumnEncryptor;
import com.agentflow.security.NoopColumnEncryptor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Optional;

/**
 * PostgreSQL 版 {@link WorkflowDefinitionStore}（U8，R14；U2 R22 扩列：条件列加密）。
 *
 * <p>把解析后的 {@link WorkflowDefinition} 序列化存入
 * {@code workflow_definitions(workflow_name, version, definition)} 表（V8 起 definition 列为
 * TEXT——密文或明文 JSON 兼容）；恢复/retry 按 checkpoint 的 name+version 反序列化取回。
 * JSON 序列化用 camelCase 分量名（与 DSL POJO 一致、自洽往返），与 YAML 解析的 SNAKE_CASE 无关。
 *
 * <p><b>R22 列加密（U2）</b>：构造注入可空 {@link ColumnEncryptor}（默认 Noop 明文，向后兼容）；
 * save 走 {@code encrypt(toJson(def))}、find/findLatest 走 {@code decrypt(raw)}——与
 * {@code PostgresCheckpointManager} 同一「可空加密器 + 边界加解密」模式，DSL/引擎零感知
 * （KTD-E2 延续）。生产由 starter 注入 {@link com.agentflow.security.ColumnEncryptors#fromEnvStrict()}。
 *
 * <p>依赖 Flyway 在启动时执行 {@code V3__workflow_definitions.sql} + {@code V8__r22_encrypt_routing_and_definitions.sql}。
 */
public final class PostgresWorkflowDefinitionStore implements WorkflowDefinitionStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper jsonMapper;
    /** U2 R22：列级静态加密（可空=不加密，默认 Noop；生产经 starter 注入 fromEnvStrict）。 */
    private final ColumnEncryptor encryptor;

    public PostgresWorkflowDefinitionStore(DataSource dataSource) {
        this(dataSource, defaultJsonMapper());
    }

    public PostgresWorkflowDefinitionStore(DataSource dataSource, ObjectMapper jsonMapper) {
        this(new JdbcTemplate(dataSource), jsonMapper, NoopColumnEncryptor.INSTANCE);
    }

    /**
     * 创建（指定列加密器）。生产由 AgentFlowAutoConfiguration 注入
     * {@link com.agentflow.security.ColumnEncryptors#fromEnvStrict()}（fail-closed 强制加密）。
     */
    public PostgresWorkflowDefinitionStore(DataSource dataSource, ColumnEncryptor encryptor) {
        this(new JdbcTemplate(dataSource), defaultJsonMapper(), encryptor);
    }

    /** 全参数构造（含加密器；包私有 seam——测试直接注入 JdbcTemplate + 加密器组合）。 */
    PostgresWorkflowDefinitionStore(JdbcTemplate jdbc, ObjectMapper jsonMapper, ColumnEncryptor encryptor) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper == null ? defaultJsonMapper() : jsonMapper;
        this.encryptor = encryptor != null ? encryptor : NoopColumnEncryptor.INSTANCE;
    }

    @Override
    public void save(String workflowName, String version, WorkflowDefinition definition) {
        // U2 R22：写路径加密（Noop 恒等）；TEXT 列无 ::jsonb cast（V8 起）
        jdbc.update(
                "INSERT INTO workflow_definitions (workflow_name, version, definition) VALUES (?, ?, ?) "
                        + "ON CONFLICT (workflow_name, version) DO UPDATE SET definition = EXCLUDED.definition",
                workflowName, version, toEncryptedJson(definition));
    }

    @Override
    public Optional<WorkflowDefinition> find(String workflowName, String version) {
        return Optional.ofNullable(jdbc.query(
                "SELECT definition FROM workflow_definitions WHERE workflow_name = ? AND version = ?",
                rs -> rs.next() ? fromJson(decryptRaw(rs.getString("definition"))) : null,
                workflowName, version));
    }

    @Override
    public Optional<WorkflowDefinition> findLatest(String workflowName) {
        return Optional.ofNullable(jdbc.query(
                "SELECT definition FROM workflow_definitions WHERE workflow_name = ? ORDER BY created_at DESC LIMIT 1",
                rs -> rs.next() ? fromJson(decryptRaw(rs.getString("definition"))) : null,
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

    /** U2 写路径：序列化后加密（Noop 恒等；AESGCM 自描述前缀，legacy 明文行读时原样返回）。 */
    private String toEncryptedJson(WorkflowDefinition def) {
        return encryptor.encrypt(toJson(def));
    }

    /** U2 读路径：先解密（Noop 恒等 / AESGCM 对非前缀值原样返回）再反序列化。 */
    private String decryptRaw(String raw) {
        return encryptor.decrypt(raw);
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
