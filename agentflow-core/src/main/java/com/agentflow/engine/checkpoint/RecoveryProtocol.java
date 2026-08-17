package com.agentflow.engine.checkpoint;

import com.agentflow.agent.AgentOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 崩溃恢复协议（KTD-3 Recovery）。
 *
 * <p>从数据库恢复工作流到可执行状态：定位崩溃点、重建 channel 快照、识别已完成节点避免 LLM 重复计费。
 *
 * <h3>恢复算法（0-based super-step）</h3>
 * <ol>
 *   <li>查最新已完成的 barrier checkpoint → latest.superStep = k（表示 super-step k 已完成）</li>
 *   <li>nextSuperStep = k + 1（若从未 barrier → 从 0 开始）</li>
 *   <li>channel 快照 = barrier checkpoint 的 channelValues（若无则空 Map）</li>
 *   <li>查崩溃层（= nextSuperStep）中 status=COMPLETED 且 output≠null 的节点 → 跳过这些节点</li>
 *   <li>把这些节点的 AgentOutput 收集为 replayOutputs——引擎按 Reducer 重放进 context，
 *       恢复崩溃前的 channel 状态（它们的 channelWrites 未进上一 barrier，否则下游读到陈旧/null 值）</li>
 * </ol>
 *
 * <h3>关键修复（v4 off-by-one）</h3>
 * <p>查询目标是 {@code nextSuperStep}（崩溃层本身），<b>不是</b> {@code nextSuperStep - 1}（已 barrier 的层）。
 * 后者会导致崩溃层中已完成的节点被重复执行、LLM 重复计费，违背 R3。
 *
 * <h3>Stray 记录防护（P0 修复 ADV-2）</h3>
 * <p>超时 abort 后仍可能有在飞 VT 完成并写出 COMPLETED 到已 abort 的 super-step。引擎 abort 时
 * 显式调用 {@code updateStatus(FAILED)}；Recovery 先查工作流状态：若为 FAILED，判定崩溃层可能含
 * stray COMPLETED（未经 barrier 合并的孤立输出），此时<b>忽略整个崩溃层的 COMPLETED 节点</b>——
 * completedNodeIds 与 replayOutputs 均为空，崩溃层整体重跑，杜绝读到孤立 channel 输出导致的下游错误。
 * 工作流状态非 FAILED（正常崩溃，非 abort）时，崩溃层 COMPLETED 节点是合法的崩溃前完成结果，跳过 + 重放。
 *
 * <p>线程安全：本协议只读 checkpoint 数据，无状态，天然线程安全。
 */
public final class RecoveryProtocol {

    private static final Logger log = LoggerFactory.getLogger(RecoveryProtocol.class);

    private final CheckpointManager checkpointManager;

    public RecoveryProtocol(CheckpointManager checkpointManager) {
        this.checkpointManager = Objects.requireNonNull(checkpointManager, "checkpointManager");
    }

    /** 暴露给 BspEngine.recoverAndExecute 续跑用。 */
    public CheckpointManager checkpointManager() {
        return checkpointManager;
    }

    /**
     * 从数据库恢复工作流到可执行状态。
     *
     * @param workflowId 工作流实例 id
     * @return ExecutionState 包含 nextSuperStep、channel 快照、已完成节点集合、重放输出
     */
    public ExecutionState recover(String workflowId) {
        Objects.requireNonNull(workflowId, "workflowId");

        // Step 1: 查找最新已完成的 barrier checkpoint
        Optional<BarrierCheckpoint> latestOpt = checkpointManager.findLatestBarrier(workflowId);

        int round;
        int nextSuperStep;
        Map<String, Object> channelSnapshot;

        if (latestOpt.isPresent()) {
            BarrierCheckpoint latest = latestOpt.get();
            // barrier 到 (round, step=k) → 下一个待执行是 (round, step=k+1)
            round = latest.round();
            nextSuperStep = latest.superStep() + 1;
            channelSnapshot = new HashMap<>(latest.channelValues());
            log.debug("恢复 wf={}: 最新 barrier round={} step={}, 下一 super-step={}",
                    workflowId, round, latest.superStep(), nextSuperStep);
        } else {
            // 从未 barrier 过 → 从 round 0、step=0 开始，channel 为空
            round = 0;
            nextSuperStep = 0;
            channelSnapshot = new HashMap<>();
            log.debug("恢复 wf={}: 无 barrier checkpoint，从 round 0 super-step 0 开始", workflowId);
        }

        // Step 2: stray 记录防护（P0 修复 ADV-2）——查工作流状态
        Optional<WorkflowStatus> statusOpt = checkpointManager.findStatus(workflowId);
        boolean aborted = statusOpt.isPresent() && statusOpt.get() == WorkflowStatus.FAILED;
        if (aborted) {
            // 引擎显式 abort（timeout 后在飞 VT 可能已写 stray COMPLETED 到崩溃层）。
            // 这些节点的 channelWrites 未经 barrier 合并，跳过它们会让下游读到陈旧/null channel。
            // 安全策略：崩溃层整体重跑——宁可 LLM 重复计费（R3 软约束），不换错误结果（正确性硬约束）。
            log.warn("恢复 wf={}: 工作流状态=FAILED（abort），崩溃层 step={} 整体重跑（忽略 stray COMPLETED）",
                    workflowId, nextSuperStep);
            return new ExecutionState(workflowId, round, nextSuperStep, channelSnapshot, Set.of(), List.of());
        }

        // Step 3: 查询崩溃 super-step（= nextSuperStep）中已完成的节点级 checkpoint
        // 🔑 查询的是 nextSuperStep 本身（崩溃层），不是 nextSuperStep-1（已 barrier 层）
        List<NodeOutputStore> completedNodes = checkpointManager.findCompletedNodes(
                workflowId, round, nextSuperStep);

        // Step 4: 构建已完成节点集合 + 重放输出（P0 修复 ADV-1）
        // 双重保护：status=COMPLETED（已在 SQL/查询层过滤） + output 非空
        // replayOutputs 收集这些节点的 AgentOutput——引擎按 Reducer 重放进 context，
        // 恢复崩溃前 channel 状态（它们的 channelWrites 未进上一 barrier，跳过不重跑但要重放输出）
        Set<String> completedNodeIds = new HashSet<>();
        List<AgentOutput> replayOutputs = new java.util.ArrayList<>();
        for (NodeOutputStore n : completedNodes) {
            if (n.output() == null) {
                continue; // 二次保护：output 非空
            }
            completedNodeIds.add(n.nodeId());
            replayOutputs.add(n.output());
            log.debug("恢复 wf={}: 跳过已完成节点 {} (step={})，输出将重放进 channel",
                    workflowId, n.nodeId(), n.superStep());
        }

        log.info("恢复 wf={}: nextSuperStep={}, 已完成节点数={}, 重放输出数={}, channelKeys={}",
                workflowId, nextSuperStep, completedNodeIds.size(), replayOutputs.size(),
                channelSnapshot.keySet());

        return new ExecutionState(workflowId, round, nextSuperStep, channelSnapshot, completedNodeIds, replayOutputs);
    }
}