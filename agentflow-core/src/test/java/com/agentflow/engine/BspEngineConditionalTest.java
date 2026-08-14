package com.agentflow.engine;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.FatalException;
import com.agentflow.dsl.EdgeDefinition;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.WorkflowDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U4 验证：BSP 引擎可达性剪枝 + 条件路由（when 谓词）。
 */
class BspEngineConditionalTest {

    private final BspEngine engine = new BspEngine();

    private static NodeDefinition node(String id, String agent) {
        return new NodeDefinition(id, agent, null, null, null, null, null, null);
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

    @Test
    @DisplayName("AE1 二路分支：verdict=approved → approve 执行、reject SKIPPED")
    void twoWayBranch() {
        WorkflowDefinition def = wf(
                List.of(node("classify", "c"), node("approve", "a"), node("reject", "r")),
                List.of(
                        whenEdge("classify", "approve", "output.verdict == 'approved'"),
                        edge("classify", "reject")));

        Set<String> called = ConcurrentHashMap.newKeySet();
        Map<String, AgentFunction> agents = Map.of(
                "c", input -> new AgentOutput(null, Map.of(), Map.of("verdict", "approved"), Map.of()),
                "a", input -> { called.add("approve"); return AgentOutput.of("approved-result"); },
                "r", input -> { called.add("reject"); return AgentOutput.of("rejected-result"); });

        WorkflowContext result = engine.execute(def, agents, Map.of());

        assertThat(called).contains("approve").doesNotContain("reject");
        assertThat(result.getValue("approve")).isEqualTo("approved-result");
        assertThat(result.getValue("reject")).isNull();
    }

    @Test
    @DisplayName("AE4 分支汇合：join 执行一次，不被跳过分支阻塞")
    void joinRunsWhenBranchSkipped() {
        WorkflowDefinition def = wf(
                List.of(node("router", "rt"), node("b1", "a"), node("b2", "b"), node("join", "j")),
                List.of(
                        whenEdge("router", "b1", "output.go == 'b1'"),
                        edge("router", "b2"),
                        edge("b1", "join"),
                        edge("b2", "join")));

        Set<String> called = ConcurrentHashMap.newKeySet();
        Map<String, AgentFunction> agents = Map.of(
                "rt", input -> new AgentOutput(null, Map.of(), Map.of("go", "b1"), Map.of()),
                "a", input -> { called.add("b1"); return AgentOutput.of("b1-out"); },
                "b", input -> { called.add("b2"); return AgentOutput.of("b2-out"); },
                "j", input -> { called.add("join"); return AgentOutput.of("joined"); });

        engine.execute(def, agents, Map.of());

        assertThat(called).contains("b1", "join").doesNotContain("b2");
    }

    @Test
    @DisplayName("AE2 无默认且谓词均 false → FAILED（无分支命中）")
    void noMatchFails() {
        WorkflowDefinition def = wf(
                List.of(node("classify", "c"), node("b1", "a"), node("b2", "b")),
                List.of(
                        whenEdge("classify", "b1", "output.verdict == 'approved'"),
                        whenEdge("classify", "b2", "output.verdict == 'rejected'")));
        Map<String, AgentFunction> agents = Map.of(
                "c", input -> new AgentOutput(null, Map.of(), Map.of("verdict", "other"), Map.of()),
                "a", input -> AgentOutput.of("b1"),
                "b", input -> AgentOutput.of("b2"));

        assertThatThrownBy(() -> engine.execute(def, agents, Map.of()))
                .isInstanceOfSatisfying(WorkflowExecutionException.class, we -> {
                    assertThat(we.failures()).hasSize(1);
                    assertThat(we.failures().get(0)).isInstanceOf(FatalException.class);
                    assertThat((FatalException) we.failures().get(0)).hasMessageContaining("无分支命中");
                });
    }

    @Test
    @DisplayName("AE5 纯 fan-out（无条件边）→ 所有节点执行（v1 行为不回归）")
    void staticFanOutUnchanged() {
        WorkflowDefinition def = wf(
                List.of(node("A", "a"), node("B", "b"), node("C", "c")),
                List.of(edge("A", "B"), edge("A", "C")));

        Set<String> called = ConcurrentHashMap.newKeySet();
        Map<String, AgentFunction> agents = Map.of(
                "a", input -> { called.add("A"); return AgentOutput.of("a"); },
                "b", input -> { called.add("B"); return AgentOutput.of("b"); },
                "c", input -> { called.add("C"); return AgentOutput.of("c"); });

        engine.execute(def, agents, Map.of());

        assertThat(called).contains("A", "B", "C");
    }

    @Test
    @DisplayName("混合：when 边命中走 when、未命中走默认边")
    void defaultEdgeTakenWhenNoWhenMatches() {
        WorkflowDefinition def = wf(
                List.of(node("classify", "c"), node("approved", "a"), node("fallback", "f")),
                List.of(
                        whenEdge("classify", "approved", "output.verdict == 'approved'"),
                        edge("classify", "fallback")));

        Set<String> called = ConcurrentHashMap.newKeySet();
        Map<String, AgentFunction> agents = Map.of(
                "c", input -> new AgentOutput(null, Map.of(), Map.of("verdict", "rejected"), Map.of()),
                "a", input -> { called.add("approved"); return AgentOutput.of("approved"); },
                "f", input -> { called.add("fallback"); return AgentOutput.of("fallback"); });

        engine.execute(def, agents, Map.of());

        assertThat(called).contains("fallback").doesNotContain("approved");
    }
}
