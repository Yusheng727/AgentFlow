package com.agentflow.engine;

/**
 * @deprecated 早期占位，无消费者。审批信号使用 {@link NodeResult.Success}/{@link NodeResult.Failure}/
 * {@link NodeResult.ApprovalRequired} 三态 + {@link com.agentflow.agent.ApprovalRequiredException}。
 */
@Deprecated
public final class ApprovalRequired {
    private ApprovalRequired() {
    }
}