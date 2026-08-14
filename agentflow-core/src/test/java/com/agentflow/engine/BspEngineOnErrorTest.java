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
 * U5 验证：on_error 终态失败兜底（跳转 cleanup、正常下游 SKIPPED、不二次跳转）。
 */
class BspEngineOnErrorTest {

    private final BspEngine engine = new BspEngine();

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

    @Test
    @DisplayName("AE3 on_error 兜底：节点失败 → cleanup 执行、正常下游 SKIPPED、工作流不 abort")
    void onErrorFallback() {
        WorkflowDefinition def = wf(
                List.of(node("A", "a", "cleanup"), node("B", "b"), node("cleanup", "cl")),
                List.of(edge("A", "B")));

        Set<String> called = ConcurrentHashMap.newKeySet();
        Map<String, AgentFunction> agents = Map.of(
                "a", input -> { throw new FatalException("A 失败"); },
                "b", input -> { called.add("B"); return AgentOutput.of("b"); },
                "cl", input -> { called.add("cleanup"); return AgentOutput.of("cleaned"); });

        WorkflowContext result = engine.execute(def, agents, Map.of());

        assertThat(called).contains("cleanup").doesNotContain("B");
        assertThat(result.getValue("cleanup")).isEqualTo("cleaned");
    }

    @Test
    @DisplayName("AE10 条件边 + on_error：成功走谓词分支、失败走 on_error（互斥）")
    void conditionalAndOnErrorMutuallyExclusive() {
        WorkflowDefinition def = wf(
                List.of(node("R", "r", "cleanup"), node("B1", "b1"), node("B2", "b2"), node("cleanup", "cl")),
                List.of(whenEdge("R", "B1", "output.verdict == 'approved'"), edge("R", "B2")));

        // 成功 → B1
        Set<String> successCalls = ConcurrentHashMap.newKeySet();
        Map<String, AgentFunction> successAgents = Map.of(
                "r", input -> new AgentOutput(null, Map.of(), Map.of("verdict", "approved"), Map.of()),
                "b1", input -> { successCalls.add("B1"); return AgentOutput.of("b1"); },
                "b2", input -> { successCalls.add("B2"); return AgentOutput.of("b2"); },
                "cl", input -> { successCalls.add("cleanup"); return AgentOutput.of("cl"); });
        engine.execute(def, successAgents, Map.of());
        assertThat(successCalls).contains("B1").doesNotContain("B2", "cleanup");

        // 失败 → cleanup
        Set<String> failCalls = ConcurrentHashMap.newKeySet();
        Map<String, AgentFunction> failAgents = Map.of(
                "r", input -> { throw new FatalException("R 失败"); },
                "b1", input -> { failCalls.add("B1"); return AgentOutput.of("b1"); },
                "b2", input -> { failCalls.add("B2"); return AgentOutput.of("b2"); },
                "cl", input -> { failCalls.add("cleanup"); return AgentOutput.of("cl"); });
        engine.execute(def, failAgents, Map.of());
        assertThat(failCalls).contains("cleanup").doesNotContain("B1", "B2");
    }

    @Test
    @DisplayName("on_error 目标自身失败 → 工作流 FAILED（不二次跳转）")
    void onErrorTargetFailureIsFatal() {
        WorkflowDefinition def = wf(
                List.of(node("A", "a", "B"), node("B", "b", "C"), node("C", "c")),
                List.of(edge("A", "C")));

        Set<String> called = ConcurrentHashMap.newKeySet();
        Map<String, AgentFunction> agents = Map.of(
                "a", input -> { throw new FatalException("A 失败"); },
                "b", input -> { called.add("B"); throw new FatalException("B 失败"); },
                "c", input -> { called.add("C"); return AgentOutput.of("c"); });

        assertThatThrownBy(() -> engine.execute(def, agents, Map.of()))
                .isInstanceOf(WorkflowExecutionException.class);
        assertThat(called).contains("B").doesNotContain("C");
    }

    @Test
    @DisplayName("无 on_error 的节点失败 → 仍 abort（失败传播不回归）")
    void noOnErrorNodeFailsStillAborts() {
        WorkflowDefinition def = wf(
                List.of(node("A", "a"), node("B", "b")),
                List.of(edge("A", "B")));
        Map<String, AgentFunction> agents = Map.of(
                "a", input -> { throw new FatalException("A 失败"); },
                "b", input -> AgentOutput.of("b"));

        assertThatThrownBy(() -> engine.execute(def, agents, Map.of()))
                .isInstanceOf(WorkflowExecutionException.class);
    }
}
