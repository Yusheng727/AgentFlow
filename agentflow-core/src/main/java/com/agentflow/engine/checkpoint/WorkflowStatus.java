package com.agentflow.engine.checkpoint;

/**
 * 工作流执行实例状态。
 *
 * <p>U5 引入，对应 {@code workflow_executions.status} 列的 CHECK 约束。
 * 状态机：PENDING → RUNNING → SUCCESS | FAILED。
 */
public enum WorkflowStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED
}