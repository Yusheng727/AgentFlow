package com.agentflow.engine;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.dsl.ChannelDefinition;
import com.agentflow.dsl.EdgeDefinition;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.Reducer;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.engine.checkpoint.RecoveryProtocol;
import com.agentflow.engine.checkpoint.WorkflowStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U5 P0 修复端到端验证：BspEngine.recoverAndExecute 消费 ExecutionState 的三条路径。
 *
 * <p>覆盖 ce-code-review adversarial 发现的两个 P0：
 * <ul>
 *   <li>ADV-1：崩溃层 COMPLETED 节点的 channelWrites 未进 barrier，replayOutputs 重放进 context</li>
 *   <li>ADV-2：FAILED 状态下崩溃层整体重跑，忽略 stray COMPLETED</li>
 * </ul>
 */
class BspEngineRecoveryTest {

    private final BspEngine engine = new BspEngine();

    // ---------- 测试夹具（与 BspEngineTest 同风格）----------

    private static NodeDefinition node(String id, String agent) {
        return new NodeDefinition(id, agent, null, null, null, null, null, null);
    }

    private static EdgeDefinition edge(String from, String to) {
        return new EdgeDefinition(from, to);
    }

    private static WorkflowDefinition wf(List<NodeDefinition> nodes, List<EdgeDefinition> edges) {
        return new WorkflowDefinition(null, null, nodes, edges);
    }

    // ========== 正常恢复：崩溃层 COMPLETED 节点跳过 + 输出重放 ==========

    @Test
    @DisplayName("P0 ADV-1 端到端：崩溃层 nodeA 已 COMPLETED → recoverAndExecute 跳过它 + 重放输出，下游读到正确 channel")
    void recoverReplaysCrashLayerOutput() {
        // 工作流：A(0)→B(1)→C(2) 串行
        WorkflowDefinition def = wf(
                List.of(node("A", "a"), node("B", "b"), node("C", "c")),
                List.of(edge("A", "B"), edge("B", "C")));

        InMemoryCheckpointManager cp = new InMemoryCheckpointManager();
        cp.initWorkflow("wf", "test", "1.0", null);

        // 模拟崩溃前状态：super-step 0（A）已 barrier 完成，channel A="a-out"
        // 用真实引擎跑 step 0 产生 barrier
        Map<String, AgentFunction> agents = Map.of(
                "a", input -> AgentOutput.of("a-out"),
                "b", input -> AgentOutput.of("B:" + input.context().getValue("A")),
                "c", input -> AgentOutput.of("C:" + input.context().getValue("B")));
        // 先跑完完整工作流，让 step 0/1 的 barrier 落盘
        engine.execute(def, agents, Map.of(), cp, new ChannelReducer(), "wf");

        // 现在模拟：在 step 2（C）执行前崩溃，但 step 1（B）的 barrier 已写
        // 为验证 ADV-1，构造一个"崩溃层有 COMPLETED 节点但 barrier 未含其输出"的场景：
        // 直接清掉 step 1 的 barrier，但保留 step 1 的 node B 的 COMPLETED 记录
        // （即 B 完成了、saveNodeOutput 写了，但崩溃在 barrier 之前）
        // —— 用一个新的 checkpoint 模拟这个中间态
        InMemoryCheckpointManager crashCp = new InMemoryCheckpointManager();
        crashCp.initWorkflow("wf2", "test", "1.0", null);
        // step 0 barrier（A 完成）
        crashCp.saveBarrier("wf2", 0, new WorkflowContext(Map.of("A", "a-out")));
        // step 1 的 B 已 COMPLETED（saveNodeOutput 已写），但 barrier 未写——崩溃窗口
        crashCp.saveNodeOutput("wf2", 1, "B", new AgentOutput(
                "B content",
                Map.of("B", "B:a-out"), // channelWrites：未进 barrier，靠 replay 恢复
                Map.of(), Map.of()));
        crashCp.updateStatus("wf2", WorkflowStatus.RUNNING); // 正常崩溃，非 abort

        AtomicInteger bRuns = new AtomicInteger();
        Map<String, AgentFunction> agents2 = Map.of(
                "a", input -> AgentOutput.of("a-out"),
                "b", input -> { bRuns.incrementAndGet(); return AgentOutput.of("B:" + input.context().getValue("A")); },
                "c", input -> AgentOutput.of("C:" + input.context().getValue("B")));

        RecoveryProtocol recovery = new RecoveryProtocol(crashCp);
        WorkflowContext result = engine.recoverAndExecute(recovery, def, agents2::get, new ChannelReducer(), "wf2");

        // 🔑 ADV-1 修复：B 的输出经 replayOutputs 重放进 context，C 能读到 "B:a-out"
        assertThat(result.getValue("B")).isEqualTo("B:a-out");
        assertThat(result.getValue("C")).isEqualTo("C:B:a-out");
        // B 未重跑（崩溃层已完成，跳过）
        assertThat(bRuns.get()).isZero();
    }

    // ========== abort 重跑：FAILED 状态忽略 stray COMPLETED ==========

    @Test
    @DisplayName("P0 ADV-2 端到端：工作流=FAILED（abort）→ 崩溃层整体重跑，stray COMPLETED 被忽略")
    void abortedWorkflowRerunsCrashLayer() {
        WorkflowDefinition def = wf(
                List.of(node("A", "a"), node("B", "b")),
                List.of(edge("A", "B")));

        InMemoryCheckpointManager cp = new InMemoryCheckpointManager();
        cp.initWorkflow("wf", "test", "1.0", null);
        // step 0 barrier 完成
        cp.saveBarrier("wf", 0, new WorkflowContext(Map.of("A", "a-out")));
        // 引擎 abort → 标记 FAILED
        cp.updateStatus("wf", WorkflowStatus.FAILED);
        // 崩溃层（step 1）有 stray COMPLETED（timeout 后在飞 VT 写出，未经 barrier）
        cp.saveNodeOutput("wf", 1, "B", AgentOutput.of("stray-output"));

        AtomicInteger bRuns = new AtomicInteger();
        Map<String, AgentFunction> agents = Map.of(
                "a", input -> AgentOutput.of("a-out"),
                "b", input -> { bRuns.incrementAndGet(); return AgentOutput.of("B:" + input.context().getValue("A")); });

        RecoveryProtocol recovery = new RecoveryProtocol(cp);
        WorkflowContext result = engine.recoverAndExecute(recovery, def, agents::get, new ChannelReducer(), "wf");

        // 🔑 ADV-2 修复：FAILED 状态忽略 stray COMPLETED，B 整体重跑
        assertThat(bRuns.get()).isEqualTo(1); // B 重跑了（不是跳过 stray 记录）
        assertThat(result.getValue("B")).isEqualTo("B:a-out"); // 重跑产出，非 stray
        // 最终状态 SUCCESS
        assertThat(cp.findStatus("wf")).contains(WorkflowStatus.SUCCESS);
    }

    @Test
    @DisplayName("恢复后正常完成 → 状态置 SUCCESS")
    void recoveryCompletesToSuccess() {
        WorkflowDefinition def = wf(List.of(node("A", "a")), List.of());
        InMemoryCheckpointManager cp = new InMemoryCheckpointManager();
        cp.initWorkflow("wf", "test", "1.0", null);
        // 无 barrier，无已完成节点 → 从 step 0 开始
        cp.updateStatus("wf", WorkflowStatus.RUNNING);

        Map<String, AgentFunction> agents = Map.of("a", input -> AgentOutput.of("a-out"));
        RecoveryProtocol recovery = new RecoveryProtocol(cp);
        engine.recoverAndExecute(recovery, def, agents::get, new ChannelReducer(), "wf");

        assertThat(cp.findStatus("wf")).contains(WorkflowStatus.SUCCESS);
    }
}
