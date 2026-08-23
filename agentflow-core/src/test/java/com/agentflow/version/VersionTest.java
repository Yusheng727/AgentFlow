package com.agentflow.version;

import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.dao.DataAccessException;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * U8 Workflow 版本管理测试（R14）。
 *
 * <p>覆盖 plan Test scenarios：版本缺省 "1.0"+WARN、多版本并存按版本取定义（恢复用旧 DAG）、
 * 冲突检测（执行版本落后于最新定义版本 → Conflict）。
 */
class VersionTest {

    // ──────────────────────── 场景 1：版本缺省 ────────────────────────

    @Test
    @DisplayName("agentflow.version 缺失 → 默认 \"1.0\"（R14）；显式版本保留")
    void versionDefaultsTo1_0() {
        // 无 version → 默认
        WorkflowDefinition noVer = parse("agentflow: {}\n" + nodes("no-ver"));
        assertThat(noVer.version()).isEqualTo("1.0");
        // 显式 version → 保留
        WorkflowDefinition v2 = parse("agentflow:\n  version: \"2.0\"\n" + nodes("v2"));
        assertThat(v2.version()).isEqualTo("2.0");
    }

    // ──────────── 场景 2&3：多版本并存 + 恢复按执行版本取定义（不用 classpath） ────────────

    @Test
    @DisplayName("定义按 (name, version) 存入 store；恢复/retry 能取到旧版本 DAG，findLatest 取最新")
    void storeKeepsPerVersionDefinitionsForRecovery() {
        InMemoryWorkflowDefinitionStore store = new InMemoryWorkflowDefinitionStore();
        WorkflowVersionManager manager = new WorkflowVersionManager(store);

        manager.recordWorkflowDefinition("supplier-risk", parseVersion("1.0"));
        manager.recordWorkflowDefinition("supplier-risk", parseVersion("2.0"));

        // 旧实例崩溃恢复：loadDefinition(name, "1.0") 返回 V1 定义（旧 DAG）
        assertThat(manager.loadDefinition("supplier-risk", "1.0"))
                .get().extracting(WorkflowDefinition::version).isEqualTo("1.0");
        assertThat(manager.loadDefinition("supplier-risk", "2.0"))
                .get().extracting(WorkflowDefinition::version).isEqualTo("2.0");
        assertThat(manager.loadDefinition("supplier-risk", "9.9")).isEmpty();
        // 最新
        assertThat(store.findLatest("supplier-risk"))
                .get().extracting(WorkflowDefinition::version).isEqualTo("2.0");
    }

    // ──────────────── 场景 4：冲突检测（执行版本落后于最新定义版本） ────────────────

    @Test
    @DisplayName("VersionConflictDetector：执行版本 ≠ 最新定义版本 → Conflict（WARN）；一致 → 无冲突")
    void detectVersionConflict() {
        InMemoryWorkflowDefinitionStore store = new InMemoryWorkflowDefinitionStore();
        WorkflowDefinitionStore r = store;
        r.save("supplier-risk", "1.0", parseVersion("1.0"));
        r.save("supplier-risk", "2.0", parseVersion("2.0"));

        VersionConflictDetector detector = new VersionConflictDetector();
        var conflict = detector.detect(store, "supplier-risk", "1.0");
        assertThat(conflict).isPresent();
        assertThat(conflict.get().latestVersion()).isEqualTo("2.0");
        assertThat(conflict.get().message()).contains("1.0").contains("2.0");
        // 一致 → 无冲突
        assertThat(detector.detect(store, "supplier-risk", "2.0")).isEmpty();
        // 无历史定义 → 无冲突
        assertThat(detector.detect(store, "unknown-name", "1.0")).isEmpty();
    }

    @Test
    @DisplayName("WorkflowVersionManager.detectConflict 透传（空 store → 无冲突）")
    void managerDetectConflict() {
        InMemoryWorkflowDefinitionStore store = new InMemoryWorkflowDefinitionStore();
        WorkflowVersionManager manager = new WorkflowVersionManager(store);
        assertThat(manager.detectConflict("greet", "1.0")).isEmpty();
        manager.recordWorkflowDefinition("greet", parseVersion("1.0"));
        manager.recordWorkflowDefinition("greet", parseVersion("3.0"));
        assertThat(manager.detectConflict("greet", "1.0"))
                .get().extracting(VersionConflictDetector.Conflict::latestVersion).isEqualTo("3.0");
    }

    // ──────────────── Checkpoint 关联 version（findVersion/findWorkflowName） ────────────────

    @Test
    @DisplayName("CheckpointManager（InMemory）记录并查询 workflowName/version，供恢复+冲突检测")
    void checkpointFindVersionAndName() {
        InMemoryCheckpointManager cp = new InMemoryCheckpointManager();
        cp.initWorkflow("wf-1", "greet", "2.0", "caller");
        assertThat(cp.findWorkflowName("wf-1")).contains("greet");
        assertThat(cp.findVersion("wf-1")).contains("2.0");
        assertThat(cp.findWorkflowName("nope")).isEmpty();
        assertThat(cp.findVersion("nope")).isEmpty();
    }

    // ──────────────── Postgres store：序列化往返 + Save/Find（FakeJdbc 覆盖 SQL 分支） ────────────────

    @Test
    @DisplayName("Postgres store JSON 序列化往返：WorkflowDefinition 保存后可还原")
    void postgresStoreJsonRoundTrip() {
        PostgresWorkflowDefinitionStore s = new PostgresWorkflowDefinitionStore(mock(DataSource.class));
        WorkflowDefinition def = parseVersion("2.0");
        WorkflowDefinition back = s.fromJson(s.toJson(def));
        assertThat(back.version()).isEqualTo("2.0");
        assertThat(back.nodeIds()).isEqualTo(def.nodeIds());
    }

    @Test
    @DisplayName("Postgres store save/find/findLatest（FakeJdbc）：按 name+version 存取，findLatest 取最新")
    void postgresStoreSaveFind() {
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate();
        PostgresWorkflowDefinitionStore s = new PostgresWorkflowDefinitionStore(jdbc, null, null);

        s.save("x", "1.0", parseVersion("1.0"));
        s.save("x", "2.0", parseVersion("2.0"));

        assertThat(s.find("x", "1.0")).get().extracting(WorkflowDefinition::version).isEqualTo("1.0");
        assertThat(s.find("x", "2.0")).get().extracting(WorkflowDefinition::version).isEqualTo("2.0");
        assertThat(s.find("x", "9.9")).isEmpty();
        assertThat(s.findLatest("x")).get().extracting(WorkflowDefinition::version).isEqualTo("2.0");
    }

    // ──────────────────────── 辅助 ────────────────────────

    private static WorkflowDefinition parseVersion(String version) {
        return parse("agentflow:\n  version: \"" + version + "\"\n" + nodes(version));
    }

    private static String nodes(String id) {
        return "nodes:\n  - { id: " + id + ", agent: mock, mock_response: \"r\" }\nedges: []\n";
    }

    private static WorkflowDefinition parse(String yaml) {
        return new WorkflowDSLParser().parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    /** 覆写 JdbcTemplate 的 update/query，模拟 workflow_definitions 表（按 name@version 存 JSON）。 */
    static class FakeJdbcTemplate extends JdbcTemplate {
        final Map<String, String> store = new HashMap<>();
        final Map<String, String> latestByName = new HashMap<>();

        @Override
        public int update(String sql, Object... args) {
            // INSERT ... VALUES (?, ?, ?) → args = [name, version, definitionJson]
            String name = (String) args[0];
            String version = (String) args[1];
            String json = (String) args[2];
            store.put(name + "@" + version, json);
            latestByName.put(name, json);
            return 1;
        }

        @Override
        public <T> T query(String sql, ResultSetExtractor<T> rse, Object... args) throws DataAccessException {
            String name = (String) args[0];
            String version = args.length > 1 ? (String) args[1] : null;
            String json = version != null ? store.get(name + "@" + version) : latestByName.get(name);
            ResultSet rs = mock(ResultSet.class);
            try {
                if (json == null) {
                    when(rs.next()).thenReturn(false);
                } else {
                    when(rs.next()).thenReturn(true);
                    when(rs.getString("definition")).thenReturn(json);
                }
                return rse.extractData(rs);
            } catch (Exception e) {
                throw new IllegalStateException("FakeJdbc query failed", e);
            }
        }
    }
}
