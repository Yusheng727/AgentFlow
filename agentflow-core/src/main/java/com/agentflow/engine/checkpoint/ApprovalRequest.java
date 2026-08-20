package com.agentflow.engine.checkpoint;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批请求（U1 审批单，随 checkpoint 持久化）。
 *
 * <p>{@code requestPayload} 为待审核的决策载荷（如付款金额/上下文摘要）；{@code contextSnapshot}
 * 为暂停点的只读 channel 快照（含兄弟节点输出、不含审批节点，供恢复执行）。{@code decidedBy}
 * 服务端从 callerId 推导，客户端不可伪造（U6 安全点）。
 */
public record ApprovalRequest(
        String approvalId,
        String workflowId,
        String nodeId,
        int round,
        int superStep,
        String description,
        Map<String, Object> requestPayload,
        Map<String, Object> contextSnapshot,
        ApprovalStatus status,
        String decidedBy,
        Instant createdAt
) {

    public ApprovalRequest {
        if (requestPayload == null) requestPayload = Map.of();
        if (contextSnapshot == null) contextSnapshot = Map.of();
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(status, "status");
    }

    /** 构造 PENDING 审批单（引擎暂停时调用）。 */
    public static ApprovalRequest pending(String workflowId, String nodeId, int round, int superStep,
                                       String description, Map<String, Object> requestPayload,
                                       Map<String, Object> contextSnapshot) {
        return new ApprovalRequest(UUID.randomUUID().toString(), workflowId, nodeId, round, superStep,
                description, requestPayload, contextSnapshot, ApprovalStatus.PENDING, null, Instant.now());
    }
}