package com.agentflow.engine.checkpoint;

import com.agentflow.agent.AgentOutput;
import com.agentflow.engine.WorkflowContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U5 RecoveryProtocol 测试（plan U5 Test scenarios）。
 *
 * <p>验证崩溃恢复的 off-by-one 修复 + 节点状态过滤（COMPLETED 跳过、
 * IN_PROGRESS 忽略、FAILED 重执行）。用 InMemoryCheckpointManager 模拟 checkpoint 数据。
 */
class RecoveryProtocolTest {

    private InMemoryCheckpointManager cm;
    private RecoveryProtocol protocol;

    @BeforeEach
    void setUp() {
        cm = new InMemoryCheckpointManager();
        protocol = new RecoveryProtocol(cm);
    }

    // ─────────────────── 正常恢复路径 ───────────────────

    @Test
    @DisplayName("从中间 super-step 崩溃恢复 → nextSuperStep 正确 + channel 快照恢复")
    void recoverFromMidExecution() {
        // 模拟：super-step 0 和 1 已 barrier 完成，崩溃发生在 super-step 2
        cm.saveBarrier("wf-1", 0, new WorkflowContext(Map.of("step0", "done")));
        cm.saveBarrier("wf-1", 1, new WorkflowContext(Map.of("step0", "done", "step1", "done")));

        ExecutionState state = protocol.recover("wf-1");

        // barrier 到 step=1 → nextSuperStep = 2（崩溃层）
        assertThat(state.nextSuperStep()).isEqualTo(2);
        // channel 快照来自 step=1 的 barrier
        assertThat(state.channelSnapshot()).containsEntry("step0", "done");
        assertThat(state.channelSnapshot()).containsEntry("step1", "done");
        // 崩溃层无已完成节点
        assertThat(state.completedNodeIds()).isEmpty();
    }

    @Test
    @DisplayName("从未 barrier 过（首次执行）→ nextSuperStep=0, 空 channel")
    void recoverFromScratch() {
        ExecutionState state = protocol.recover("wf-1");

        assertThat(state.nextSuperStep()).isEqualTo(0);
        assertThat(state.channelSnapshot()).isEmpty();
        assertThat(state.completedNodeIds()).isEmpty();
    }

    @Test
    @DisplayName("崩溃层有 COMPLETED 节点 → 跳过这些节点不重执行（LLM 不重复计费）")
    void skipCompletedNodesInCrashLayer() {
        // super-step 0 和 1 barrier 完成
        cm.saveBarrier("wf-1", 0, new WorkflowContext(Map.of("a", 1)));
        cm.saveBarrier("wf-1", 1, new WorkflowContext(Map.of("a", 1, "b", 2)));
        // super-step 2（崩溃层）中 nodeX 已 COMPLETED，nodeY IN_PROGRESS，nodeZ FAILED
        cm.saveNodeOutput("wf-1", 2, "nodeX", AgentOutput.of("x output")); // COMPLETED
        // nodeY — 不写（IN_PROGRESS 状态无法通过 saveNodeOutput 创建，此方法只写 COMPLETED）
        // nodeZ FAILED — 模拟：直接通过内部 map 注入（需要额外 API）→ 用 findCompleted 不返回代替

        ExecutionState state = protocol.recover("wf-1");

        assertThat(state.nextSuperStep()).isEqualTo(2);
        // 只有 nodeX（COMPLETED）在跳过集合中
        assertThat(state.completedNodeIds()).containsExactly("nodeX");
        // nodeY（未写入）和 nodeZ（未写入）不在跳过集合 → 引擎会重执行
    }

    // ─────────────────── P0 修复验证（ADV-1 / ADV-2）───────────────────

    @Test
    @DisplayName("P0 ADV-1: 崩溃层 COMPLETED 节点的输出进 replayOutputs，供引擎重放进 channel")
    void replayOutputsCollectedForCrashLayerCompleted() {
        // super-step 0 barrier 完成（channel: a=1）
        cm.saveBarrier("wf-1", 0, new WorkflowContext(Map.of("a", 1)));
        // 崩溃层（step=1）nodeX 已 COMPLETED，其 channelWrites 写了 b=2（未进 barrier）
        cm.saveNodeOutput("wf-1", 1, "nodeX", new AgentOutput(
                "x content",
                Map.of("b", 2), // channelWrites —— 关键：这个未进 barrier，靠 replayOutputs 恢复
                Map.of(), Map.of()));

        ExecutionState state = protocol.recover("wf-1");

        assertThat(state.nextSuperStep()).isEqualTo(1);
        assertThat(state.completedNodeIds()).containsExactly("nodeX");
        // 🔑 P0 ADV-1 修复：nodeX 的 AgentOutput 在 replayOutputs 中，引擎据此重放 b=2 进 channel
        assertThat(state.replayOutputs()).hasSize(1);
        assertThat(state.replayOutputs().getFirst().channelWrites()).containsEntry("b", 2);
    }

    @Test
    @DisplayName("P0 ADV-2: 工作流状态=FAILED（abort）→ 崩溃层整体重跑，忽略 stray COMPLETED")
    void abortedWorkflowIgnoresStrayCompleted() {
        // super-step 0 barrier 完成
        cm.saveBarrier("wf-1", 0, new WorkflowContext(Map.of("a", 1)));
        // 引擎 abort 显式标记 FAILED
        cm.updateStatus("wf-1", WorkflowStatus.FAILED);
        // 崩溃层（step=1）有 stray COMPLETED（timeout 后在飞 VT 写出，未经 barrier 合并）
        cm.saveNodeOutput("wf-1", 1, "strayNode", AgentOutput.of("stray"));

        ExecutionState state = protocol.recover("wf-1");

        assertThat(state.nextSuperStep()).isEqualTo(1);
        // 🔑 P0 ADV-2 修复：FAILED 状态下忽略 stray COMPLETED，崩溃层整体重跑
        assertThat(state.completedNodeIds()).isEmpty();
        assertThat(state.replayOutputs()).isEmpty();
    }

    @Test
    @DisplayName("P0 ADV-2: 工作流状态=RUNNING（正常崩溃非 abort）→ 崩溃层 COMPLETED 合法，跳过+重放")
    void runningWorkflowTreatsCompletedAsLegitimate() {
        cm.saveBarrier("wf-1", 0, new WorkflowContext(Map.of("a", 1)));
        // 正常崩溃（进程挂了，未 abort 标记），状态仍 RUNNING
        cm.updateStatus("wf-1", WorkflowStatus.RUNNING);
        cm.saveNodeOutput("wf-1", 1, "nodeX", AgentOutput.of("x"));

        ExecutionState state = protocol.recover("wf-1");

        // 非 FAILED → 崩溃层 COMPLETED 是合法的崩溃前完成结果，跳过 + 重放
        assertThat(state.completedNodeIds()).containsExactly("nodeX");
        assertThat(state.replayOutputs()).hasSize(1);
    }

    @Test
    @DisplayName("P0 ADV-2: 无工作流状态记录（NoopCheckPointManager 或未 initWorkflow）→ 不阻断，正常恢复")
    void noStatusRecordDoesNotBlockRecovery() {
        cm.saveBarrier("wf-1", 0, new WorkflowContext(Map.of("a", 1)));
        // 未调 initWorkflow / updateStatus → findStatus 返回 empty
        cm.saveNodeOutput("wf-1", 1, "nodeX", AgentOutput.of("x"));

        ExecutionState state = protocol.recover("wf-1");

        // 无状态记录视为非 abort，崩溃层 COMPLETED 合法
        assertThat(state.completedNodeIds()).containsExactly("nodeX");
        assertThat(state.replayOutputs()).hasSize(1);
    }

    @Test
    @DisplayName("崩溃层无任何 COMPLETED 节点 → 全部重执行")
    void noCompletedNodesInCrashLayer() {
        cm.saveBarrier("wf-1", 0, new WorkflowContext(Map.of("a", 1)));
        // 崩溃层（step=1）无任何节点写入

        ExecutionState state = protocol.recover("wf-1");

        assertThat(state.nextSuperStep()).isEqualTo(1);
        assertThat(state.completedNodeIds()).isEmpty();
    }

    // ─────────────────── 边界情况 ───────────────────

    @Test
    @DisplayName("仅 barrier step=0 完成 → nextSuperStep=1")
    void onlyFirstBarrierComplete() {
        cm.saveBarrier("wf-1", 0, new WorkflowContext(Map.of("init", true)));

        ExecutionState state = protocol.recover("wf-1");

        assertThat(state.nextSuperStep()).isEqualTo(1);
        assertThat(state.channelSnapshot()).containsEntry("init", true);
    }

    @Test
    @DisplayName("多 workflow 隔离：查询不跨 workflow")
    void workflowIsolation() {
        cm.saveBarrier("wf-1", 0, new WorkflowContext(Map.of("wf1", true)));
        cm.saveBarrier("wf-1", 1, new WorkflowContext(Map.of("wf1", true, "wf1_step1", true)));
        cm.saveNodeOutput("wf-1", 2, "nodeA", AgentOutput.of("wf1"));
        cm.saveBarrier("wf-2", 0, new WorkflowContext(Map.of("wf2", true)));

        ExecutionState state1 = protocol.recover("wf-1");
        ExecutionState state2 = protocol.recover("wf-2");

        // wf-1 恢复到 step=2
        assertThat(state1.nextSuperStep()).isEqualTo(2);
        assertThat(state1.completedNodeIds()).contains("nodeA");
        // wf-2 恢复到 step=1（仅 barrier step=0 存在）
        assertThat(state2.nextSuperStep()).isEqualTo(1);
        assertThat(state2.completedNodeIds()).isEmpty();
    }

    // ─────────────────── off-by-one 修复验证 ───────────────────

    @Nested
    @DisplayName("Off-by-one 修复验证（查询 nextSuperStep，非 nextSuperStep-1）")
    class OffByOneFix {

        @Test
        @DisplayName("崩溃层节点在 nextSuperStep（非 nextSuperStep-1）可查到")
        void crashLayerNodesAreInNextSuperStep() {
            // barrier step=1 完成 → nextSuperStep = 2（崩溃层）
            cm.saveBarrier("wf-1", 0, new WorkflowContext(Map.of()));
            cm.saveBarrier("wf-1", 1, new WorkflowContext(Map.of()));
            // 崩溃层（step=2）有已完成节点
            cm.saveNodeOutput("wf-1", 2, "crashNode", AgentOutput.of("crashed"));

            ExecutionState state = protocol.recover("wf-1");

            assertThat(state.nextSuperStep()).isEqualTo(2);
            // 🔑 off-by-one 修复：crashNode 在 skipped 集合中（不被重复执行）
            assertThat(state.completedNodeIds()).contains("crashNode");
        }

        @Test
        @DisplayName("已 barrier 层的节点不在 nextSuperStep 查询范围")
        void barrierLayerNodesNotInNextSuperStep() {
            // super-step 0 barrier 完成，step=0 有已完成节点
            cm.saveNodeOutput("wf-1", 0, "step0Node", AgentOutput.of("step0"));
            cm.saveBarrier("wf-1", 0, new WorkflowContext(Map.of()));
            // 崩溃层（step=1）有已完成节点
            cm.saveNodeOutput("wf-1", 1, "step1Node", AgentOutput.of("step1"));

            ExecutionState state = protocol.recover("wf-1");

            assertThat(state.nextSuperStep()).isEqualTo(1);
            // 只跳过崩溃层（step=1）的节点，不跳过 step=0 的节点
            assertThat(state.completedNodeIds())
                    .contains("step1Node")
                    .doesNotContain("step0Node");
        }
    }
}