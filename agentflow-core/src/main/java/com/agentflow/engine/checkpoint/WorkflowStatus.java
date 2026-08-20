package com.agentflow.engine.checkpoint;

/**
 * 管理工作流状态（U8：加 {@code AWAITING_APPROVAL}——审批中暂停）。
 */
public enum WorkflowStatus {
    PENDING,
    RUNNING,
    AWAITING_APPROVAL,
    SUCCESS,
    FAILED
}