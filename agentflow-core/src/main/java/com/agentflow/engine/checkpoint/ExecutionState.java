package com.agentflow.engine.checkpoint;

import com.agentflow.agent.AgentOutput;

import java.util.List;
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
 *   <li><b>重放 {@code replayOutputs}</b>：崩溃层已完成节点的 channelWrites 未进上一 barrier，
 *       引擎须把这些输出按 Reducer 合并进 context，恢复崩溃前的 channel 状态（P0 修复 ADV-1）</li>
 * </ul>
 *
 * <h3>stray 记录防护（P0 修复 ADV-2）</h3>
 * <p>若工作流状态为 {@link WorkflowStatus#FAILED}（引擎 abort 时显式标记），说明崩溃层可能含
 * timeout 后在飞 VT 写出的 stray COMPLETED 记录——此时 {@code completedNodeIds} 与
 * {@code replayOutputs} 均为空，崩溃层整体重跑，杜绝读到未经 barrier 合并的孤立 channel 输出。
 *
 * @param workflowId        待恢复的工作流实例 id
 * @param nextSuperStep     下一个待执行的 super-step 编号（0-based）
 * @param channelSnapshot   从最新 barrier checkpoint 恢复的 channel 快照
 * @param completedNodeIds  崩溃层中已完成（COMPLETED）的节点 id 集合，引擎跳过
 * @param replayOutputs     崩溃层已完成节点的 AgentOutput（按声明序），引擎重放进 context
 */
public record ExecutionState(
        String workflowId,
        int nextSuperStep,
        Map<String, Object> channelSnapshot,
        Set<String> completedNodeIds,
        List<AgentOutput> replayOutputs
) {
    public ExecutionState {
        if (channelSnapshot == null) {
            channelSnapshot = Map.of();
        }
        if (completedNodeIds == null) {
            completedNodeIds = Set.of();
        }
        if (replayOutputs == null) {
            replayOutputs = List.of();
        }
    }
}