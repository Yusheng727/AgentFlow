package com.agentflow.engine;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.FatalException;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.EdgeDefinition;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.observability.ExecutionTraceRegistry;
import com.agentflow.observability.NodeTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U4 验证：迭代轮次执行（外层 while 循环 + 双 active 集合 + 回边路由 + 终止条件）。
 */
class BspEngineLoopTest {

    private final BspEngine engine = new BspEngine();

    private static NodeDefinition node(String id, String agent) {
        return new NodeDefinition(id, agent, null, null, null, null, null, null);
    }

    private static EdgeDefinition edge(String from, String to) {
        return new EdgeDefinition(from, to);
    }

    private static EdgeDefinition loopEdge(String from, String to, String when, int maxIterations) {
        return new EdgeDefinition(from, to, when, true, maxIterations);
    }

    private static WorkflowDefinition wf(List<NodeDefinition> nodes, List<EdgeDefinition> edges) {
        return new WorkflowDefinition(null, null, nodes, edges);
    }

    /** 反思循环：draft → critique，回边 critique→draft（score<0.8）、退出边 critique→finalize。 */
    private static WorkflowDefinition reflectionLoop() {
        return wf(
                List.of(node("draft", "d"), node("critique", "c"), node("finalize", "f")),
                List.of(
                        edge("draft", "critique"),
                        loopEdge("critique", "draft", "output.score < 0.8", 3),
                        edge("critique", "finalize")));
    }

    /** critique：第 1 次返回 score 低于阈值（触发回边），之后返回高于阈值（退出）。 */
    private static AgentFunction convergingCritique(AtomicInteger critiqueCalls) {
        return input -> {
            int n = critiqueCalls.incrementAndGet();
            double score = n == 1 ? 0.5 : 0.9;
            // content 供喂回（写 channel=critique），structuredOutput 供 when 谓词 output.score 求值
            return new AgentOutput("score=" + score, Map.of(), Map.of("score", score), Map.of());
        };
    }

    @Test
    @DisplayName("AE1 二轮收敛：draft 执行 2 次、finalize 执行 1 次")
    void reflectionLoopConverges() {
        AtomicInteger draftCalls = new AtomicInteger();
        AtomicInteger critiqueCalls = new AtomicInteger();
        AtomicInteger finalizeCalls = new AtomicInteger();
        Map<String, AgentFunction> agents = Map.of(
                "d", input -> { draftCalls.incrementAndGet(); return AgentOutput.of("draft"); },
                "c", convergingCritique(critiqueCalls),
                "f", input -> { finalizeCalls.incrementAndGet(); return AgentOutput.of("final"); });

        engine.execute(reflectionLoop(), agents, Map.of());

        assertThat(draftCalls.get()).isEqualTo(2);
        assertThat(critiqueCalls.get()).isEqualTo(2);
        assertThat(finalizeCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("AE2 迭代上限：when 恒真 → 达到 max_iterations 后 FAILED「迭代超限」")
    void iterationCapFails() {
        Map<String, AgentFunction> agents = Map.of(
                "d", input -> AgentOutput.of("draft"),
                "c", input -> new AgentOutput("score=0.1", Map.of(), Map.of("score", 0.1), Map.of()),
                "f", input -> AgentOutput.of("final"));

        assertThatThrownBy(() -> engine.execute(reflectionLoop(), agents, Map.of()))
                .isInstanceOfSatisfying(WorkflowExecutionException.class, we -> {
                    assertThat(we.failures()).hasSize(1);
                    assertThat(we.failures().get(0)).isInstanceOf(FatalException.class);
                    assertThat((FatalException) we.failures().get(0)).hasMessageContaining("迭代超限");
                });
    }

    @Test
    @DisplayName("AE5 喂回可见：第 2 轮 draft 读到第 1 轮 critique 输出")
    void feedbackVisible() {
        List<Object> draftSeesCritique = new CopyOnWriteArrayList<>();
        AtomicInteger critiqueCalls = new AtomicInteger();
        Map<String, AgentFunction> agents = Map.of(
                "d", input -> {
                    draftSeesCritique.add(input.context().getValue("critique"));
                    return AgentOutput.of("draft");
                },
                "c", convergingCritique(critiqueCalls),
                "f", input -> AgentOutput.of("final"));

        engine.execute(reflectionLoop(), agents, Map.of());

        // draft 第 1 轮读 critique=null（尚未执行），第 2 轮读 score=0.5（第 1 轮 critique 输出）
        assertThat(draftSeesCritique).hasSize(2);
        assertThat(draftSeesCritique.get(0)).isNull();
        assertThat(draftSeesCritique.get(1)).isEqualTo("score=0.5");
    }

    @Test
    @DisplayName("AE7 静态回归：无回边工作流逐层执行、行为与 v2 一致")
    void staticRegression() {
        WorkflowDefinition def = wf(
                List.of(node("A", "a"), node("B", "b"), node("C", "c")),
                List.of(edge("A", "B"), edge("B", "C")));

        Set<String> called = ConcurrentHashMap.newKeySet();
        Map<String, AgentFunction> agents = Map.of(
                "a", input -> { called.add("A"); return AgentOutput.of("a"); },
                "b", input -> { called.add("B"); return AgentOutput.of("b"); },
                "c", input -> { called.add("C"); return AgentOutput.of("c"); });

        engine.execute(def, agents, Map.of());

        assertThat(called).contains("A", "B", "C");
    }

    @Test
    @DisplayName("R7 无可执行节点终止：叶子节点无出边 → 正常完成，不抛异常")
    void noExecutableNodeTerminates() {
        WorkflowDefinition def = wf(List.of(node("only", "a")), List.of());
        Map<String, AgentFunction> agents = Map.of("a", input -> AgentOutput.of("done"));

        engine.execute(def, agents, Map.of()); // 单节点无出边 → nextActive 空 → 收敛，正常完成
    }

    @Test
    @DisplayName("agent-native：节点经 AgentInput.round() 感知当前迭代轮次（round 0 → 1）")
    void nodeObservesRound() {
        List<Integer> draftRounds = new CopyOnWriteArrayList<>();
        AtomicInteger critiqueCalls = new AtomicInteger();
        Map<String, AgentFunction> agents = Map.of(
                "d", input -> { draftRounds.add(input.round()); return AgentOutput.of("draft"); },
                "c", convergingCritique(critiqueCalls),
                "f", input -> AgentOutput.of("final"));

        engine.execute(reflectionLoop(), agents, Map.of());

        // draft 第 1 轮 round=0、第 2 轮 round=1——节点能感知自己在第几轮（无需 stateful counter 注入）
        assertThat(draftRounds).containsExactly(0, 1);
    }

    @Test
    @DisplayName("agent-native：trace 区分跨轮重复执行的同 nodeId（draft 的 NodeTrace.round 0 和 1）")
    void traceDistinguishesRounds() {
        ExecutionTraceRegistry registry = new ExecutionTraceRegistry();
        BspEngine engineWithTrace = new BspEngine(new DAGLayerer(), null, null, null, registry);
        AtomicInteger critiqueCalls = new AtomicInteger();
        Map<String, AgentFunction> agents = Map.of(
                "d", input -> {
                    // 自定义 AgentFunction 需手动写 NodeTrace（MockAgentFunction 在 adapter 模块会写）
                    if (input.trace() != null) {
                        NodeTrace nt = new NodeTrace("draft", "d");
                        input.trace().addNode(nt);
                        nt.succeed("draft", 0, 0);
                    }
                    return AgentOutput.of("draft");
                },
                "c", convergingCritique(critiqueCalls),
                "f", input -> AgentOutput.of("final"));

        engineWithTrace.execute(reflectionLoop(), agents, Map.of(),
                new InMemoryCheckpointManager(), new ChannelReducer(), "wf-trace");

        List<NodeTrace> draftTraces = registry.get("wf-trace").snapshot().nodes().stream()
                .filter(n -> n.nodeId().equals("draft"))
                .toList();
        // draft 执行 2 次：round 0 + round 1——trace 能区分跨轮重复，诊断「为何迭代 N 次」可重建
        assertThat(draftTraces).hasSize(2);
        assertThat(draftTraces).extracting(NodeTrace::round).containsExactlyInAnyOrder(0, 1);
    }
}
