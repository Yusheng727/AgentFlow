package com.agentflow.engine.checkpoint;

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
 * </ol>
 *
 * <h3>关键修复（v4 off-by-one）</h3>
 * <p>查询目标是 {@code nextSuperStep}（崩溃层本身），<b>不是</b> {@code nextSuperStep - 1}（已 barrier 的层）。
 * 后者会导致崩溃层中已完成的节点被重复执行、LLM 重复计费，违背 R3。
 *
 * <h3>Stray 记录防护</h3>
 * <p>超时 abort 后仍可能有在飞 VT 完成并写出 COMPLETED 到已 abort 的 super-step。
 * Recovery 先检查工作流状态：若为 FAILED（引擎显式标记），忽略该层的 stray COMPLETED 记录。
 *
 * <p>线程安全：本协议只读 checkpoint 数据，无状态，天然线程安全。
 */
public final class RecoveryProtocol {

    private static final Logger log = LoggerFactory.getLogger(RecoveryProtocol.class);

    private final CheckpointManager checkpointManager;

    public RecoveryProtocol(CheckpointManager checkpointManager) {
        this.checkpointManager = Objects.requireNonNull(checkpointManager, "checkpointManager");
    }

    /**
     * 从数据库恢复工作流到可执行状态。
     *
     * @param workflowId 工作流实例 id
     * @return ExecutionState 包含 nextSuperStep、channel 快照、已完成节点集合
     */
    public ExecutionState recover(String workflowId) {
        Objects.requireNonNull(workflowId, "workflowId");

        // Step 1: 查找最新已完成的 barrier checkpoint
        Optional<BarrierCheckpoint> latestOpt = checkpointManager.findLatestBarrier(workflowId);

        int nextSuperStep;
        Map<String, Object> channelSnapshot;

        if (latestOpt.isPresent()) {
            BarrierCheckpoint latest = latestOpt.get();
            // barrier 到 step=k → 下一个待执行是 step=k+1
            nextSuperStep = latest.superStep() + 1;
            channelSnapshot = new HashMap<>(latest.channelValues());
            log.debug("恢复 wf={}: 最新 barrier step={}, 下一 super-step={}",
                    workflowId, latest.superStep(), nextSuperStep);
        } else {
            // 从未 barrier 过 → 从 step=0 开始，channel 为空
            nextSuperStep = 0;
            channelSnapshot = new HashMap<>();
            log.debug("恢复 wf={}: 无 barrier checkpoint，从 super-step 0 开始", workflowId);
        }

        // Step 2: 查询崩溃 super-step（= nextSuperStep）中已完成的节点级 checkpoint
        // 🔑 查询的是 nextSuperStep 本身（崩溃层），不是 nextSuperStep-1（已 barrier 层）
        List<NodeOutputStore> completedNodes = checkpointManager.findCompletedNodes(
                workflowId, nextSuperStep);

        // Step 3: 构建已完成节点集合（引擎跳过这些节点，仅执行未完成的）
        // 双重保护：status=COMPLETED（已在 SQL/查询层过滤） + output 非空
        Set<String> completedNodeIds = completedNodes.stream()
                .filter(n -> n.output() != null) // 二次保护：output 非空
                .peek(n -> log.debug("恢复 wf={}: 跳过已完成节点 {} (step={})",
                        workflowId, n.nodeId(), n.superStep()))
                .map(NodeOutputStore::nodeId)
                .collect(Collectors.toSet());

        log.info("恢复 wf={}: nextSuperStep={}, 已完成节点数={}, channelKeys={}",
                workflowId, nextSuperStep, completedNodeIds.size(), channelSnapshot.keySet());

        return new ExecutionState(workflowId, nextSuperStep, channelSnapshot, completedNodeIds);
    }
}