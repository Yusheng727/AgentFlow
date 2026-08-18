package com.agentflow.api;

import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.engine.checkpoint.WorkflowStatus;
import com.agentflow.version.InMemoryWorkflowDefinitionStore;
import com.agentflow.version.WorkflowDefinitionStore;
import com.agentflow.version.WorkflowVersionManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WorkflowExecutionService} 单元测试（U1，KTD-B/KTD-A）。
 *
 * <p>覆盖：成功终态、引擎失败终态、定义瞬时缺失重试、定义永久缺失抛 Fatal 且不改状态。
 */
class WorkflowExecutionServiceTest {

    private static final String SIMPLE_YAML = """
            agentflow: { version: "1.0" }
            nodes:
              - { id: A, agent: a }
            edges: []
            """;

    private final WorkflowDSLParser parser = new WorkflowDSLParser();
    private InMemoryCheckpointManager cm;
    private InMemoryWorkflowDefinitionStore store;
    private WorkflowVersionManager versionManager;
    private NodeRegistry registry;

    @BeforeEach
    void setUp() {
        cm = new InMemoryCheckpointManager();
        store = new InMemoryWorkflowDefinitionStore();
        versionManager = new WorkflowVersionManager(store);
        registry = new NodeRegistry(Map.of("a", input -> AgentOutput.of("output-from-a")));
    }

    private WorkflowDefinition parse() {
        return parser.parse(new ByteArrayInputStream(SIMPLE_YAML.getBytes(StandardCharsets.UTF_8)));
    }

    private WorkflowExecutionService service(int retry, long backoff) {
        return new WorkflowExecutionService(new BspEngine(), registry, cm,
                new ChannelReducer(), versionManager, retry, backoff);
    }

    @Test
    @DisplayName("成功：PENDING→RUNNING→SUCCESS，引擎被执行")
    void runExecutesToSuccess() {
        versionManager.recordWorkflowDefinition("wf", parse());
        cm.initWorkflow("wf-1", "wf", "1.0", "caller");
        WorkflowExecutionService svc = service(3, 1000L);

        svc.run("wf-1", "wf", "1.0", Map.of());

        assertThat(cm.findStatus("wf-1")).contains(WorkflowStatus.SUCCESS);
    }

    @Test
    @DisplayName("引擎失败：状态 FAILED 且异常 rethrow")
    void runFailureMarksFailedAndRethrows() {
        NodeRegistry failing = new NodeRegistry(Map.of("a", input -> {
            throw new RuntimeException("boom");
        }));
        WorkflowExecutionService svc = new WorkflowExecutionService(new BspEngine(), failing, cm,
                new ChannelReducer(), versionManager, 3, 1000L);
        versionManager.recordWorkflowDefinition("wf", parse());
        cm.initWorkflow("wf-2", "wf", "1.0", "caller");

        assertThatThrownBy(() -> svc.run("wf-2", "wf", "1.0", Map.of()))
                .isInstanceOf(RuntimeException.class);
        assertThat(cm.findStatus("wf-2")).contains(WorkflowStatus.FAILED);
    }

    @Test
    @DisplayName("定义瞬时缺失：首次空、重试命中 → 执行成功")
    void definitionTransientMissingRetriesAndSucceeds() {
        WorkflowVersionManager flaky = new WorkflowVersionManager(new FlakyFindStore());
        WorkflowExecutionService svc = new WorkflowExecutionService(new BspEngine(), registry, cm,
                new ChannelReducer(), flaky, 3, 5L);
        flaky.recordWorkflowDefinition("wf", parse());
        cm.initWorkflow("wf-3", "wf", "1.0", "caller");

        svc.run("wf-3", "wf", "1.0", Map.of());

        assertThat(cm.findStatus("wf-3")).contains(WorkflowStatus.SUCCESS);
    }

    @Test
    @DisplayName("定义永久缺失：重试耗尽抛 Fatal，不 updateStatus（保持 PENDING）")
    void definitionPermanentMissingThrowsWithoutStatusChange() {
        WorkflowExecutionService svc = service(3, 5L);
        cm.initWorkflow("wf-4", "wf", "1.0", "caller");

        assertThatThrownBy(() -> svc.run("wf-4", "wf", "1.0", Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cm.findStatus("wf-4")).contains(WorkflowStatus.PENDING);
    }

    /** 前 N 次 find 返回空，之后正常——模拟定义写入的瞬时不可见。 */
    private static final class FlakyFindStore implements WorkflowDefinitionStore {
        private final WorkflowDefinitionStore delegate = new InMemoryWorkflowDefinitionStore();
        private int calls = 0;

        @Override
        public Optional<WorkflowDefinition> find(String workflowName, String version) {
            if (calls++ < 2) {
                return Optional.empty();
            }
            return delegate.find(workflowName, version);
        }

        @Override
        public Optional<WorkflowDefinition> findLatest(String workflowName) {
            return delegate.findLatest(workflowName);
        }

        @Override
        public void save(String workflowName, String version, WorkflowDefinition definition) {
            delegate.save(workflowName, version, definition);
        }
    }
}
