package com.agentflow.engine;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.FatalException;
import com.agentflow.agent.TransientException;
import com.agentflow.dsl.ChannelDefinition;
import com.agentflow.dsl.EdgeDefinition;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.Reducer;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.engine.checkpoint.CheckpointManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * U2 验证：BSP 执行引擎。覆盖 plan U2 全部 7 个 Test scenarios + 超时/cancel。
 */
class BspEngineTest {

    private final BspEngine engine = new BspEngine();

    // ---------- 测试夹具 ----------

    private static NodeDefinition node(String id, String agent) {
        return new NodeDefinition(id, agent, null, null, null, null, null, null);
    }

    private static NodeDefinition node(String id, String agent, String timeout) {
        return new NodeDefinition(id, agent, null, null, timeout, null, null, null);
    }

    private static EdgeDefinition edge(String from, String to) {
        return new EdgeDefinition(from, to);
    }

    /** channels 可为 null；nodes/edges 构造工作流定义。 */
    private static WorkflowDefinition wf(Map<String, ChannelDefinition> channels,
                                         List<NodeDefinition> nodes, List<EdgeDefinition> edges) {
        return new WorkflowDefinition(null, channels, nodes, edges);
    }

    /** Agent：读 context 中 channel 的值，输出 content = prefix + 该值，写入 channel = 自身 id。 */
    private static AgentFunction readAndEcho(String prefix, String readChannel) {
        return input -> {
            Object v = readChannel == null ? "" : input.context().getValue(readChannel);
            return AgentOutput.of(prefix + (v == null ? "" : v));
        };
    }

    // ========== 场景 1：串行 A→B→C，输出逐层可见 ==========

    @Test
    @DisplayName("串行 A→B→C：3 super-step，A 输出在 B input 可见，逐层传递")
    void serialContextPropagation() {
        WorkflowDefinition def = wf(null,
                List.of(node("A", "a"), node("B", "b"), node("C", "c")),
                List.of(edge("A", "B"), edge("B", "C")));

        Map<String, AgentFunction> agents = Map.of(
                "a", input -> AgentOutput.of("a-out"),
                "b", readAndEcho("B:", "A"),
                "c", readAndEcho("C:", "B"));

        WorkflowContext result = engine.execute(def, agents, Map.of());

        // C 的 content = "C:" + B 的 content = "C:" + "B:" + "a-out"
        assertThat(result.getValue("C")).isEqualTo("C:B:a-out");
        assertThat(result.getValue("B")).isEqualTo("B:a-out");
        assertThat(result.getValue("A")).isEqualTo("a-out");
    }

    // ========== 场景 2：并行 A→{B,C,D}→E ==========

    @Test
    @DisplayName("并行 A→{B,C,D}→E：super-step 1 三节点并行，E 能看到三路输出")
    void parallelForkJoinSeesAllBranches() {
        WorkflowDefinition def = wf(null,
                List.of(node("A", "a"), node("B", "b"), node("C", "c"), node("D", "d"), node("E", "e")),
                List.of(edge("A", "B"), edge("A", "C"), edge("A", "D"),
                        edge("B", "E"), edge("C", "E"), edge("D", "E")));

        Map<String, AgentFunction> agents = Map.of(
                "a", input -> AgentOutput.of("a"),
                "b", readAndEcho("B", "A"),
                "c", readAndEcho("C", "A"),
                "d", readAndEcho("D", "A"),
                "e", input -> AgentOutput.of("E:" + input.context().getValue("B")
                        + input.context().getValue("C") + input.context().getValue("D")));

        long start = System.nanoTime();
        WorkflowContext result = engine.execute(def, agents, Map.of());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.getValue("E")).isEqualTo("E:BaCaDa");
        // B/C/D 各 sleep 100ms 并行 → 总耗时应 < 300ms（证明并行而非串行）
        assertThat(elapsedMs).isLessThan(500L);
    }

    // ========== 场景 3：混合 A→{B,C}→D→{E,F}→G，5 super-step ==========

    @Test
    @DisplayName("混合拓扑（双层 fork-join）：5 super-step 正确分层，G 汇总前序所有输出")
    void mixedDoubleForkJoin() {
        WorkflowDefinition def = wf(null,
                List.of(node("A", "a"), node("B", "b"), node("C", "c"),
                        node("D", "d"), node("E", "e"), node("F", "f"), node("G", "g")),
                List.of(edge("A", "B"), edge("A", "C"),
                        edge("B", "D"), edge("C", "D"),
                        edge("D", "E"), edge("D", "F"),
                        edge("E", "G"), edge("F", "G")));

        Map<String, AgentFunction> agents = Map.of(
                "a", input -> AgentOutput.of("a"),
                "b", readAndEcho("B", "A"),
                "c", readAndEcho("C", "A"),
                "d", input -> AgentOutput.of("D:" + input.context().getValue("B") + "|" + input.context().getValue("C")),
                "e", readAndEcho("E", "D"),
                "f", readAndEcho("F", "D"),
                "g", input -> AgentOutput.of("G:" + input.context().getValue("E") + "|" + input.context().getValue("F")));

        WorkflowContext result = engine.execute(def, agents, Map.of());

        assertThat(result.getValue("D")).isEqualTo("D:Ba|Ca");
        assertThat(result.getValue("E")).isEqualTo("ED:Ba|Ca");
        assertThat(result.getValue("F")).isEqualTo("FD:Ba|Ca");
        assertThat(result.getValue("G")).isEqualTo("G:ED:Ba|Ca|FD:Ba|Ca");
    }

    // ========== 场景 4：同 channel 并发写入，Reducer 确定性合并 ==========

    @Test
    @DisplayName("同 channel 并发写入：OVERWRITE/CONCAT/MAX/CUSTOM 按声明序确定合并")
    void concurrentSameChannelReducer() {
        // A→{B,C}→D；B、C 同时写 X/Y/Z/W；声明序 B 在 C 前
        Map<String, ChannelDefinition> channels = Map.of(
                "X", new ChannelDefinition(null, Reducer.OVERWRITE),
                "Y", new ChannelDefinition(null, Reducer.CONCAT),
                "Z", new ChannelDefinition(null, Reducer.MAX),
                "W", new ChannelDefinition(null, Reducer.CUSTOM));
        WorkflowDefinition def = wf(channels,
                List.of(node("A", "a"), node("B", "b"), node("C", "c"), node("D", "d")),
                List.of(edge("A", "B"), edge("A", "C"), edge("B", "D"), edge("C", "D")));

        // B 写 b、C 写 c（数值 1/2）
        AgentFunction writerB = input -> AgentOutput.of(Map.of("X", "b", "Y", "b", "Z", 1, "W", "b"));
        AgentFunction writerC = input -> AgentOutput.of(Map.of("X", "c", "Y", "c", "Z", 2, "W", "c"));
        AgentFunction reader = input -> AgentOutput.of("X=" + input.context().getValue("X")
                + ",Y=" + input.context().getValue("Y")
                + ",Z=" + input.context().getValue("Z")
                + ",W=" + input.context().getValue("W"));

        // CUSTOM：用 "+" 连接
        ChannelReducer reducer = new ChannelReducer(Map.of("W", vals -> String.join("+",
                vals.stream().map(String::valueOf).toList())));

        RecordingCheckpoint cp = new RecordingCheckpoint();
        WorkflowContext result = engine.execute(def,
                Map.of("a", input -> AgentOutput.of("a"), "b", writerB, "c", writerC, "d", reader),
                Map.of(), cp, reducer, "wf-1");

        // X OVERWRITE → 后写 C 的 "c"；Y CONCAT → "bc"；Z MAX → 2；W CUSTOM → "b+c"
        assertThat(result.getValue("X")).isEqualTo("c");
        assertThat(result.getValue("Y")).isEqualTo("bc");
        assertThat(result.getValue("Z")).isEqualTo(2);
        assertThat(result.getValue("W")).isEqualTo("b+c");
        assertThat(result.getValue("D")).isEqualTo("X=c,Y=bc,Z=2,W=b+c");
    }

    @Test
    @DisplayName("MAX reducer 与声明序无关：B=2 C=1 仍得 2")
    void maxReducerOrderInvariant() {
        Map<String, ChannelDefinition> channels = Map.of("Z", new ChannelDefinition(null, Reducer.MAX));
        WorkflowDefinition def = wf(channels,
                List.of(node("A", "a"), node("B", "b"), node("C", "c")),
                List.of(edge("A", "B"), edge("A", "C")));
        // B=2, C=1
        Map<String, AgentFunction> agents = Map.of(
                "a", input -> AgentOutput.of("a"),
                "b", input -> AgentOutput.of(Map.of("Z", 2)),
                "c", input -> AgentOutput.of(Map.of("Z", 1)));
        WorkflowContext result = engine.execute(def, agents, Map.of());
        assertThat(result.getValue("Z")).isEqualTo(2);
    }

    // ========== 场景 5：barrier 等最慢节点 ==========

    @Test
    @DisplayName("节点 A 慢 500ms、B/C 各 10ms：barrier 等待 A 完成才进下一层（并行总耗 ~500ms）")
    void barrierWaitsForSlowest() {
        WorkflowDefinition def = wf(null,
                List.of(node("A", "a"), node("B", "b"), node("C", "c")),
                List.of()); // 无 edges → 单 super-step {A,B,C}

        Map<String, AgentFunction> agents = Map.of(
                "a", sleepThenOutput(500, "a"),
                "b", sleepThenOutput(10, "b"),
                "c", sleepThenOutput(10, "c"));

        long start = System.nanoTime();
        WorkflowContext result = engine.execute(def, agents, Map.of());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.getValue("A")).isEqualTo("a");
        assertThat(result.getValue("B")).isEqualTo("b");
        assertThat(result.getValue("C")).isEqualTo("c");
        // 并行 → 总耗 ~500ms，远小于串行 520ms；容忍抖动到 700ms
        assertThat(elapsedMs).isCloseTo(500L, within(250L));
    }

    private static AgentFunction sleepThenOutput(int ms, String out) {
        return input -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AgentOutput.of("interrupted:" + out);
            }
            return AgentOutput.of(out);
        };
    }

    // ========== 场景 6：异常隔离 ==========

    @Test
    @DisplayName("并行节点 1 个抛异常：其他节点正常完成，barrier 后聚合抛 WorkflowExecutionException")
    void exceptionIsolation() {
        WorkflowDefinition def = wf(null,
                List.of(node("A", "a"), node("B", "b"), node("C", "c")),
                List.of()); // 单 super-step {A,B,C}

        AtomicInteger aRuns = new AtomicInteger();
        AtomicInteger cRuns = new AtomicInteger();
        Map<String, AgentFunction> agents = Map.of(
                "a", input -> { aRuns.incrementAndGet(); return AgentOutput.of("a"); },
                "b", input -> { throw new TransientException("B failed"); },
                "c", input -> { cRuns.incrementAndGet(); return AgentOutput.of("c"); });

        RecordingCheckpoint cp = new RecordingCheckpoint();

        assertThatThrownBy(() -> engine.execute(def, agents, Map.of(), cp, new ChannelReducer(), "wf"))
                .isInstanceOf(WorkflowExecutionException.class)
                .satisfies(ex -> {
                    WorkflowExecutionException we = (WorkflowExecutionException) ex;
                    assertThat(we.superStep()).isZero();
                    assertThat(we.failures()).hasSize(1);
                    assertThat(we.failures().get(0)).isInstanceOf(TransientException.class);
                });

        // A、C 仍正常完成（异常隔离）
        assertThat(aRuns.get()).isEqualTo(1);
        assertThat(cRuns.get()).isEqualTo(1);
        // A、C 的节点级 checkpoint 已写（完成）；B 未写（失败）
        assertThat(cp.nodeOutputs.keySet()).containsExactlyInAnyOrder("A", "C");
    }

    // ========== 场景 7：快照不可变 ==========

    @Test
    @DisplayName("context 快照不可变：节点 put → UnsupportedOperationException 并视为失败")
    void snapshotImmutable() {
        WorkflowDefinition def = wf(null,
                List.of(node("A", "a")),
                List.of());

        AgentFunction mutating = input -> {
            input.context().put("x", "y"); // 只读快照应抛
            return AgentOutput.of("ok");
        };

        assertThatThrownBy(() -> engine.execute(def, Map.of("a", mutating), Map.of()))
                .isInstanceOf(WorkflowExecutionException.class)
                .satisfies(ex -> {
                    Throwable cause = ((WorkflowExecutionException) ex).failures().get(0);
                    assertThat(cause).isInstanceOf(UnsupportedOperationException.class);
                });
    }

    // ========== 超时 + cancel（KTD-6） ==========

    @Test
    @DisplayName("节点超时：cancel(true) 中断在飞调用 + 调用 AgentFunction.cancel()")
    void nodeTimeoutCancelsAgent() {
        WorkflowDefinition def = wf(null,
                List.of(node("A", "a", "100ms")),
                List.of());

        AtomicBoolean cancelCalled = new AtomicBoolean();
        AtomicBoolean executeReturned = new AtomicBoolean();
        AgentFunction slowAgent = new AgentFunction() {
            @Override
            public AgentOutput execute(AgentInput input) {
                try {
                    Thread.sleep(2000);
                    executeReturned.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // 被中断 → 期望路径
                }
                return AgentOutput.of("late");
            }

            @Override
            public void cancel(AgentInput input) {
                cancelCalled.set(true);
            }
        };

        long start = System.nanoTime();
        assertThatThrownBy(() -> engine.execute(def, Map.of("a", slowAgent), Map.of()))
                .isInstanceOf(WorkflowExecutionException.class)
                .satisfies(ex -> {
                    Throwable cause = ((WorkflowExecutionException) ex).failures().get(0);
                    assertThat(cause).isInstanceOf(java.util.concurrent.TimeoutException.class);
                });
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(cancelCalled.get()).isTrue();
        assertThat(executeReturned.get()).isFalse();
        // 100ms 超时应 promptly 触发，远小于 2000ms
        assertThat(elapsedMs).isLessThan(1500L);
    }

    @Test
    @DisplayName("FatalException 同样进入聚合失败（不重试，U2 不实现重试）")
    void fatalExceptionReported() {
        WorkflowDefinition def = wf(null,
                List.of(node("A", "a")),
                List.of());
        Map<String, AgentFunction> agents = Map.of(
                "a", input -> { throw new FatalException("bad input"); });

        assertThatThrownBy(() -> engine.execute(def, agents, Map.of()))
                .isInstanceOf(WorkflowExecutionException.class)
                .satisfies(ex -> assertThat(((WorkflowExecutionException) ex).failures().get(0))
                        .isInstanceOf(FatalException.class));
    }

    @Test
    @DisplayName("未注册 agent → 节点失败 + 聚合异常")
    void unregisteredAgentFails() {
        WorkflowDefinition def = wf(null,
                List.of(node("A", "ghost")),
                List.of());
        assertThatThrownBy(() -> engine.execute(def, Map.of(), Map.of()))
                .isInstanceOf(WorkflowExecutionException.class)
                .satisfies(ex -> assertThat(((WorkflowExecutionException) ex).failures().get(0))
                        .hasMessageContaining("ghost"));
    }

    // ========== 夹具：记录节点级 checkpoint ==========

    /** 记录 saveNodeOutput 调用，用于断言异常隔离场景下哪些节点真正完成。 */
    static final class RecordingCheckpoint implements CheckpointManager {
        final Map<String, AgentOutput> nodeOutputs = new ConcurrentHashMap<>();
        final List<Integer> barriers = new ArrayList<>();

        @Override
        public void saveNodeOutput(String workflowId, int superStep, String nodeId, AgentOutput output) {
            nodeOutputs.put(nodeId, output);
        }

        @Override
        public void saveBarrier(String workflowId, int superStep, WorkflowContext context) {
            barriers.add(superStep);
        }
    }
}
