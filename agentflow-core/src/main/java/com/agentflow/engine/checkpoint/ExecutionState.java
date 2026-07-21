package com.agentflow.engine.checkpoint;

import java.util.Map;
import java.util.Set;

/**
 * Recovery 恢复结果——描述工作流崩溃后引擎应如何恢复执行。
 *
 * <p>U5 引入，由 {@link RecoveryProtocol#recover} 构建，BspEngine 消费：
 * <ul>
 *   <li>从 {@code nextSuperStep} 重新开始执行</li>
 *   <li>用 {@code channelSnapshot} 重建 WorkflowContext</li>
 *   <li>跳过 {@code completedNodeIds} 中的节点（已 COMPLETED，不重跑 LLM）</li>
 * </ul>
 *
 * @param workflowId        待恢复的工作流实例 id
 * @param nextSuperStep     下一个待执行的 super-step 编号（0-based）
 * @param channelSnapshot   从最新 barrier checkpoint 恢复的 channel 快照
 * @param completedNodeIds  崩溃层中已完成（COMPLETED）的节点 id 集合，引擎跳过
 */
public record ExecutionState(
        String workflowId,
        int nextSuperStep,
        Map<String, Object> channelSnapshot,
        Set<String> completedNodeIds
) {
    public ExecutionState {
        if (channelSnapshot == null) {
            channelSnapshot = Map.of();
        }
        if (completedNodeIds == null) {
            completedNodeIds = Set.of();
        }
    }
}