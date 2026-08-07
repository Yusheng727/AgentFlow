package com.agentflow.engine;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.checkpoint.NoopCheckpointManager;
import com.agentflow.observability.AgentFlowMetrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U7 指标挂钩引擎测试：AgentFlowMetrics 注入 BspEngine 后，
 * 工作流完成记 {@code workflow.executed}{status}、每节点完成记 {@code node.duration}{agent}。
 *
 * <p>覆盖：成功路径（SUCCESS 计数 + 每节点耗时样本）、失败路径（FAILED 计数）、metrics 为 null 时 no-op（向后兼容）。
 */
class BspEngineMetricsTest {

    private SimpleMeterRegistry registry;
    private AgentFlowMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AgentFlowMetrics(registry);
    }

    /** 回显 mock_response 的内联 agent。 */
    private static final AgentFunction ECHO = input -> AgentOutput.of(input.mockResponse());

    private static WorkflowDefinition parse(String yaml) throws Exception {
        return new WorkflowDSLParser().parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("成功工作流：workflow.executed{success} 计 1 + 每节点 node.duration{agent} 有样本")
    void successRecordsWorkflowSuccessAndNodeDurations() throws Exception {
        BspEngine engine = new BspEngine(new DAGLayerer(), null, null, null, null, metrics);
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: A, agent: mock, mock_response: "a" }
                  - { id: B, agent: mock, mock_response: "${A}-b" }
                edges:
                  - { from: A, to: B }
                """;

        engine.execute(parse(yaml), new NodeRegistry(Map.of("mock", ECHO)), Map.of(),
                new NoopCheckpointManager(), new ChannelReducer(), "wf-metrics-success");

        // 成功计 1、失败计 0
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_EXECUTED, "status", "success").count()).isEqualTo(1.0);
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_EXECUTED, "status", "failed").count()).isZero();
        // 2 个节点（agent=mock）各记一次耗时
        Timer timer = registry.get(AgentFlowMetrics.NODE_DURATION).tags("agent", "mock").timer();
        assertThat(timer.count()).isEqualTo(2L);
    }

    @Test
    @DisplayName("失败工作流：workflow.executed{failed} 计 1，成功计 0（不双计）")
    void failureRecordsWorkflowFailed() throws Exception {
        AgentFunction boom = input -> { throw new RuntimeException("boom"); };
        BspEngine engine = new BspEngine(new DAGLayerer(), null, null, null, null, metrics);
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: A, agent: mock, mock_response: "a" }
                """;

        assertThatThrownBy(() -> engine.execute(parse(yaml), new NodeRegistry(Map.of("mock", boom)), Map.of(),
                new NoopCheckpointManager(), new ChannelReducer(), "wf-metrics-fail"))
                .isInstanceOf(WorkflowExecutionException.class);

        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_EXECUTED, "status", "failed").count()).isEqualTo(1.0);
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_EXECUTED, "status", "success").count()).isZero();
    }

    @Test
    @DisplayName("metrics 为 null（旧构造器）：执行不 NPE、不记指标")
    void nullMetricsIsNoop() throws Exception {
        BspEngine engine = new BspEngine(new DAGLayerer()); // 无 metrics
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: A, agent: mock, mock_response: "a" }
                """;

        WorkflowContext ctx = engine.execute(parse(yaml), new NodeRegistry(Map.of("mock", ECHO)), Map.of(),
                new NoopCheckpointManager(), new ChannelReducer(), "wf-metrics-null");

        assertThat(ctx).isNotNull();
        assertThat(registry.getMeters().size()).isZero(); // 未注入 metrics → 无任何指标
    }
}
