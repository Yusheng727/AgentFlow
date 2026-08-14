package com.agentflow.engine;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.FatalException;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.EdgeDefinition;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.engine.checkpoint.NoopCheckpointManager;
import com.agentflow.observability.ExecutionTrace;
import com.agentflow.observability.ExecutionTraceRegistry;
import com.agentflow.observability.NodeTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U6 验证：trace 记录路由决策 + SKIPPED 节点 + on_error 兜底三终态。
 */
class ConditionalTraceTest {

    private static NodeDefinition node(String id, String agent) {
        return new NodeDefinition(id, agent, null, null, null, null, null, null);
    }

    private static NodeDefinition node(String id, String agent, String onError) {
        return new NodeDefinition(id, agent, null, null, null, null, null, null, onError);
    }

    private static EdgeDefinition edge(String from, String to) {
        return new EdgeDefinition(from, to);
    }

    private static EdgeDefinition whenEdge(String from, String to, String when) {
        return new EdgeDefinition(from, to, when);
    }

    private static WorkflowDefinition wf(List<NodeDefinition> nodes, List<EdgeDefinition> edges) {
        return new WorkflowDefinition(null, null, nodes, edges);
    }

    private BspEngine engineWithTrace(ExecutionTraceRegistry registry) {
        return new BspEngine(new DAGLayerer(), null, null, null, registry);
    }

    @Test
    @DisplayName("条件路由 → trace 记录命中边 + SKIPPED 节点")
    void traceRecordsRoutingAndSkipped() {
        ExecutionTraceRegistry registry = new ExecutionTraceRegistry();
        BspEngine engine = engineWithTrace(registry);
        WorkflowDefinition def = wf(
                List.of(node("classify", "c"), node("approve", "a"), node("reject", "r")),
                List.of(whenEdge("classify", "approve", "output.verdict == 'approved'"),
                        edge("classify", "reject")));
        Map<String, AgentFunction> agents = Map.of(
                "c", input -> new AgentOutput(null, Map.of(), Map.of("verdict", "approved"), Map.of()),
                "a", input -> AgentOutput.of("approved"),
                "r", input -> AgentOutput.of("rejected"));

        engine.execute(def, agents, Map.of(), new NoopCheckpointManager(), new ChannelReducer(), "wf-1");

        ExecutionTrace.Snapshot snap = registry.snapshot("wf-1");
        assertThat(snap.routingDecisions()).contains("classify->approve");
        NodeTrace reject = snap.nodes().stream().filter(n -> n.nodeId().equals("reject")).findFirst().orElseThrow();
        assertThat(reject.status()).isEqualTo(NodeTrace.Status.SKIPPED);
    }

    @Test
    @DisplayName("on_error 兜底 → trace 标记兜底完成 + 记录 on_error 跳转")
    void traceRecordsOnErrorFallback() {
        ExecutionTraceRegistry registry = new ExecutionTraceRegistry();
        BspEngine engine = engineWithTrace(registry);
        WorkflowDefinition def = wf(
                List.of(node("A", "a", "cleanup"), node("B", "b"), node("cleanup", "cl")),
                List.of(edge("A", "B")));
        Map<String, AgentFunction> agents = Map.of(
                "a", input -> { throw new FatalException("A 失败"); },
                "b", input -> AgentOutput.of("b"),
                "cl", input -> AgentOutput.of("cleaned"));

        engine.execute(def, agents, Map.of(), new NoopCheckpointManager(), new ChannelReducer(), "wf-2");

        ExecutionTrace.Snapshot snap = registry.snapshot("wf-2");
        assertThat(snap.completedViaOnError()).isTrue();
        assertThat(snap.routingDecisions()).contains("A->cleanup");
        assertThat(snap.status()).isEqualTo(ExecutionTrace.Status.COMPLETED);
    }

    @Test
    @DisplayName("静态 DAG 完成 → 非兜底完成（回归）")
    void staticDagNotFallback() {
        ExecutionTraceRegistry registry = new ExecutionTraceRegistry();
        BspEngine engine = engineWithTrace(registry);
        WorkflowDefinition def = wf(
                List.of(node("A", "a"), node("B", "b")),
                List.of(edge("A", "B")));
        Map<String, AgentFunction> agents = Map.of(
                "a", input -> AgentOutput.of("a"),
                "b", input -> AgentOutput.of("b"));

        engine.execute(def, agents, Map.of(), new NoopCheckpointManager(), new ChannelReducer(), "wf-3");

        ExecutionTrace.Snapshot snap = registry.snapshot("wf-3");
        assertThat(snap.completedViaOnError()).isFalse();
        assertThat(snap.routingDecisions()).contains("A->B");
    }
}
