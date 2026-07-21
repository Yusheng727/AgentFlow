package com.agentflow.api.security;

import com.agentflow.engine.checkpoint.CheckpointManager;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;
import java.util.Optional;

/**
 * Workflow 所有权校验器（R21 防 IDOR）。
 *
 * <p>校验当前调用者（X-API-Key hash）是否为 workflow 的创建者。
 * 用于 WorkflowController 等 Controller 的 per-resource 访问控制：
 * <ul>
 *   <li>{@code GET /api/workflows/{id}/status} — 仅创建者可查状态</li>
 *   <li>{@code GET /api/workflows/{id}/trace}  — 仅创建者可查 trace</li>
 *   <li>{@code POST /api/workflows/{id}/retry} — 仅创建者可触发重试（防他人消耗 LLM 成本）</li>
 * </ul>
 *
 * <p>校验失败 → {@link OwnershipException}（Controller 统一处理返回 403）。
 *
 * <p>用法：
 * <pre>{@code
 * String callerId = (String) request.getAttribute(ApiKeyAuthFilter.CALLER_ID_ATTR);
 * ownershipChecker.requireOwnership(workflowId, callerId);
 * }</pre>
 */
public final class WorkflowOwnershipChecker {

    private final CheckpointManager checkpointManager;

    public WorkflowOwnershipChecker(CheckpointManager checkpointManager) {
        this.checkpointManager = Objects.requireNonNull(checkpointManager, "checkpointManager");
    }

    /**
     * 强制校验所有权：非创建者抛 {@link OwnershipException}。
     *
     * @param workflowId 工作流 id
     * @param callerId   当前调用者（X-API-Key 的 SHA-256 hash）
     * @throws OwnershipException 若非创建者或 workflow 不存在
     */
    public void requireOwnership(String workflowId, String callerId) {
        if (!isOwner(workflowId, callerId)) {
            throw new OwnershipException(workflowId, callerId);
        }
    }

    /**
     * 检查 callerId 是否为 workflowId 的创建者。
     *
     * @return true 若 callerId 是创建者；false 若否或 workflow 不存在
     */
    public boolean isOwner(String workflowId, String callerId) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(callerId, "callerId");
        Optional<String> createdBy = checkpointManager.findCreatedBy(workflowId);
        return createdBy.isPresent() && createdBy.get().equals(callerId);
    }

    /** 从 request attribute 提取 callerId。 */
    public static String callerIdFrom(HttpServletRequest request) {
        return (String) request.getAttribute(ApiKeyAuthFilter.CALLER_ID_ATTR);
    }

    // ──────────────────────────── 异常 ────────────────────────────

    /** 所有权校验失败异常（Controller 统一捕获返回 403）。 */
    public static final class OwnershipException extends RuntimeException {
        private final String workflowId;
        private final String callerId;

        OwnershipException(String workflowId, String callerId) {
            super("无权访问 workflow " + workflowId + "（caller=" + maskCaller(callerId) + "）");
            this.workflowId = workflowId;
            this.callerId = callerId;
        }

        public String getWorkflowId() { return workflowId; }
        public String getCallerId()  { return callerId; }

        private static String maskCaller(String id) {
            return id == null ? "null" : id.substring(0, Math.min(8, id.length())) + "...";
        }
    }
}