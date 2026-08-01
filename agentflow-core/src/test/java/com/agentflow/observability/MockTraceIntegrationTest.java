package com.agentflow.observability;

import com.agentflow.agent.AgentExecutionException;
import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.checkpoint.NoopCheckpointManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BspEngine trace 注入集成测试（U7，KTD-2 trace 穿线验证）。
 *
 * <p>用内联 AgentFunction 验证：BspEngine 注入 ExecutionTraceRegistry 后，每个 AgentInput 携带
 * 同一 ExecutionTrace 引用，AgentFunction 可据此写 NodeTrace（模拟 MockAgentFunction 的真实行为）。
 * 真实 MockAgentFunction 的写入行为由 agentflow-adapters 模块的测试覆盖（依赖 core，可调 MockAgentFunction）。
 */
class MockTraceIntegrationTest {

    /** 内联 AgentFunction：从 AgentInput.trace() 取 trace，构造 NodeTrace.succeed（模拟 mock 写 trace）。 */
    private static final AgentFunction TRACE_WRITING_MOCK = new AgentFunction() {
        @Override
        public AgentOutput execute(AgentInput input) throws AgentExecutionException {
            ExecutionTrace trace = input.trace();
            NodeTrace n = trace != null ? new NodeTrace(input.nodeId(), input.agentName()) : null;
            if (n != null) {
                trace.addNode(n);
                n.succeed(input.mockResponse(), 0L, 0L);
            }
            return AgentOutput.of(input.mockResponse());
        }
    };

    @Test
    @DisplayName("trace 穿线：BspEngine + ExecutionTraceRegistry → AgentInput.trace() 非空，3 节点全写入")
    void traceThreadingAllNodes() throws Exception {
        ExecutionTraceRegistry registry = new ExecutionTraceRegistry();
        BspEngine engine = new BspEngine(new DAGLayerer(), null, null, null, registry);

        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: A, agent: mock, mock_response: "stepA" }
                  - { id: B, agent: mock, mock_response: "${A}-stepB" }
                  - { id: C, agent: mock, mock_response: "${B}-stepC" }
                edges:
                  - { from: A, to: B }
                  - { from: B, to: C }
                """;
        WorkflowDefinition def = new WorkflowDSLParser().parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        NodeRegistry reg = new NodeRegistry(Map.of("mock", TRACE_WRITING_MOCK));
        String wfId = "wf-trace-thread-1";
        engine.execute(def, reg, Map.of(), new NoopCheckpointManager(), new ChannelReducer(), wfId);

        ExecutionTrace.Snapshot snap = registry.snapshot(wfId);
        assertThat(snap).isNotNull();
        assertThat(snap.nodes()).hasSize(3);
        assertThat(snap.nodes()).extracting(NodeTrace::nodeId).containsExactlyInAnyOrder("A", "B", "C");
        assertThat(snap.nodes()).allSatisfy(n ->
                assertThat(n.status()).isEqualTo(NodeTrace.Status.SUCCESS));
        assertThat(snap.status()).isEqualTo(ExecutionTrace.Status.COMPLETED);
    }

    @Test
    @DisplayName("未注入 registry（旧构造器）→ AgentInput.trace() 为 null 不抛，行为与 U2-U6 一致")
    void noRegistryBackwardCompat() throws Exception {
        BspEngine engine = new BspEngine();

        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: A, agent: mock, mock_response: "x" }
                edges: []
                """;
        WorkflowDefinition def = new WorkflowDSLParser().parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        NodeRegistry reg = new NodeRegistry(Map.of("mock", TRACE_WRITING_MOCK));
        // 不抛
        engine.execute(def, reg, Map.of());
    }
}
