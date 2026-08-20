package com.agentflow.engine.checkpoint;

/**
 * 审批单状态机（U1）：PENDING → APPROVED | REJECTED。
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}