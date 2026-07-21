package com.agentflow.engine.checkpoint;

import com.agentflow.agent.AgentOutput;

import java.time.Instant;

/**
 * 节点级 checkpoint 存储记录（映射 {@code workflow_node_outputs} 表一行）。
 *
 * <p>U5 引入，供 {@link PostgresCheckpointManager} 和 {@link InMemoryCheckpointManager}
 * 持久化 + {@link RecoveryProtocol} 查询崩溃层已完成节点。Updater 可复现 COMPLETED 状态
 * 不变式：output 非空且 status=COMPLETED。
 *
 * @param workflowId     所属工作流实例 id
 * @param superStep      所在 super-step（0-based）
 * @param nodeId         节点 id
 * @param output         Agent 执行输出（可空：IN_PROGRESS/FAILED 时为空）
 * @param status         节点状态
 * @param tokensConsumed token 消耗（从 AgentOutput.metadata 提取，可空）
 * @param completedAt    完成时间（可空）
 */
public record NodeOutputStore(
        String workflowId,
        int superStep,
        String nodeId,
        AgentOutput output,
        NodeStatus status,
        Integer tokensConsumed,
        Instant completedAt
) {

    /** 紧凑构造器：COMPLETED 状态不变式校验。 */
    public NodeOutputStore {
        if (status == NodeStatus.COMPLETED) {
            if (output == null) {
                throw new IllegalArgumentException("COMPLETED node must have non-null output");
            }
        }
    }

    /** 创建 IN_PROGRESS 记录（output 为空）。 */
    public static NodeOutputStore inProgress(String workflowId, int superStep, String nodeId) {
        return new NodeOutputStore(workflowId, superStep, nodeId, null, NodeStatus.IN_PROGRESS, null, null);
    }

    /** 创建 COMPLETED 记录。 */
    public static NodeOutputStore completed(String workflowId, int superStep, String nodeId,
                                            AgentOutput output, Integer tokensConsumed, Instant completedAt) {
        return new NodeOutputStore(workflowId, superStep, nodeId, output, NodeStatus.COMPLETED, tokensConsumed, completedAt);
    }

    /** 创建 FAILED 记录。 */
    public static NodeOutputStore failed(String workflowId, int superStep, String nodeId) {
        return new NodeOutputStore(workflowId, superStep, nodeId, null, NodeStatus.FAILED, null, null);
    }
}