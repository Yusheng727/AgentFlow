package com.agentflow.engine.checkpoint;

/**
 * 节点执行状态。
 *
 * <p>U5 引入，对应 {@code workflow_node_outputs.status} 列的 CHECK 约束。
 * COMPLETED 为终态（不可覆盖），FAILED 可被 retry 后升级为 COMPLETED。
 */
public enum NodeStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}