package com.agentflow.engine;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.FatalException;
import com.agentflow.dsl.EdgeDefinition;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.engine.checkpoint.RecoveryProtocol;
import com.agentflow.engine.checkpoint.WorkflowStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U7 验证：checkpoint 持久化路由决策，恢复期重算 SKIPPED，不复活已跳节点。
 */
class RecoveryConditionalTest {

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
    @DisplayName("execute 持久化路由决策（已走边）")
    void routingDecisionsPersistedDuringExecute() {
        WorkflowDefinition def = wf(
                List.of(node("R", "r"), node("B1", "b1"), node("B2", "b2")),
                List.of(whenEdge("R", "B1", "output.verdict == 'approved'"), edge("R", "B2")));
        InMemoryCheckpointManager cp = new InMemoryCheckpointManager();
        cp.initWorkflow("wf", "test", "1.0", null);
        Map<String, AgentFunction> agents = Map.of(
                "r", input -> new AgentOutput(null, Map.of(), Map.of("verdict", "approved"), Map.of()),
                "b1", input -> AgentOutput.of("b1"),
                "b2", input -> AgentOutput.of("b2"));

        engine.execute(def, agents, Map.of(), cp, new ChannelReducer(), "wf");

        assertThat(cp.findRoutingDecisions("wf")).contains("R->B1").doesNotContain("R->B2");
    }

    @Test
    @DisplayName("AE8 崩溃恢复按路由决策重算 SKIPPED：被跳节点不复活、不重复计费")
    void recoveryDoesNotReviveSkippedNode() {
        // R → (B1 when / B2 default)，B1/B2 → J。R 走 B1，B2 被 SKIPPED。
        WorkflowDefinition def = wf(
                List.of(node("R", "r"), node("B1", "b1"), node("B2", "b2"), node("J", "j")),
                List.of(whenEdge("R", "B1", "output.verdict == 'approved'"), edge("R", "B2"),
                        edge("B1", "J"), edge("B2", "J")));

        // 模拟崩溃：step 0（R）barrier 已落盘 + 路由决策 R→B1 已持久化；崩溃于 step 1 前
        InMemoryCheckpointManager cp = new InMemoryCheckpointManager();
        cp.initWorkflow("wf", "test", "1.0", null);
        cp.saveBarrier("wf", 0, new WorkflowContext(Map.of("R", "approved")));
        cp.saveRoutingDecisions("wf", 0, List.of("R->B1"));
        cp.updateStatus("wf", WorkflowStatus.RUNNING);

        Set<String> called = ConcurrentHashMap.newKeySet();
        Map<String, AgentFunction> agents = Map.of(
                "r", input -> AgentOutput.of("approved"),
                "b1", input -> { called.add("B1"); return AgentOutput.of("b1"); },
                "b2", input -> { called.add("B2"); return AgentOutput.of("b2"); },
                "j", input -> { called.add("J"); return AgentOutput.of("j"); });

        RecoveryProtocol recovery = new RecoveryProtocol(cp);
        WorkflowContext result = engine.recoverAndExecute(recovery, def, agents::get, new ChannelReducer(), "wf");

        assertThat(called).contains("B1", "J").doesNotContain("B2");
        assertThat(result.getValue("J")).isEqualTo("j");
    }

    @Test
    @DisplayName("on_error 恢复：持久化 A->cleanup 后崩溃 → 恢复不复活 A 正常下游、cleanup 执行")
    void recoveryReplaysOnErrorPath() {
        // A 失败走 on_error → cleanup；A 正常下游 B 应被剪枝。
        WorkflowDefinition def = wf(
                List.of(node("A", "a", "cleanup"), node("B", "b"), node("cleanup", "cl")),
                List.of(edge("A", "B")));

        InMemoryCheckpointManager cp = new InMemoryCheckpointManager();
        cp.initWorkflow("wf", "test", "1.0", null);
        cp.saveBarrier("wf", 0, new WorkflowContext(Map.of()));
        cp.saveRoutingDecisions("wf", 0, List.of("A->cleanup"));
        cp.updateStatus("wf", WorkflowStatus.RUNNING);

        Set<String> called = ConcurrentHashMap.newKeySet();
        Map<String, AgentFunction> agents = Map.of(
                "a", input -> { throw new FatalException("A 失败"); },
                "b", input -> { called.add("B"); return AgentOutput.of("b"); },
                "cl", input -> { called.add("cleanup"); return AgentOutput.of("cleaned"); });

        RecoveryProtocol recovery = new RecoveryProtocol(cp);
        engine.recoverAndExecute(recovery, def, agents::get, new ChannelReducer(), "wf");

        assertThat(called).contains("cleanup").doesNotContain("B");
    }
}
