package com.agentflow.api;

import com.agentflow.api.security.AdminApiKeys;
import com.agentflow.api.security.ApiKeyAuthFilter;
import com.agentflow.api.security.WorkflowOwnershipChecker;
import com.agentflow.api.security.WorkflowOwnershipChecker.OwnershipException;
import com.agentflow.engine.checkpoint.ApprovalDecision;
import com.agentflow.engine.checkpoint.ApprovalRequest;
import com.agentflow.engine.checkpoint.ApprovalStatus;
import com.agentflow.engine.checkpoint.CheckpointManager;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * HITL 审批 REST 端点（U6）：查待批列表 + 提交审批决策。
 *
 * <p>路由 {@code /api/workflows/{wfId}/approvals}：
 * <ul>
 *   <li>{@code GET .../pending} — 列出该工作流的 PENDING 审批（<b>精简投影</b>：approvalId/nodeId/
 *       description/status/createdAt；<b>不下发</b> requestPayload/contextSnapshot——防敏感载荷旁路泄漏，review P2）</li>
 *   <li>{@code POST .../{approvalId}} — 提交决策（APPROVE/REJECT），续跑工作流</li>
 * </ul>
 *
 * <p><b>安全模型</b>：
 * <ul>
 *   <li>所有权：审批人 = 有权访问该工作流的外呼方。创建者（ownership）或 admin
 *       （env {@code AGENTFLOW_ADMIN_API_KEYS}，逗号分隔多值）可查/批；其余 403（防 IDOR）。</li>
 *   <li><b>decidedBy 服务端推导</b>：请求体仅 {@code {decision}}，客户端传入的 decidedBy 一律忽略——
 *       {@code decidedBy} = 当前调用者 X-API-Key 的 SHA-256 hash（防审批审计身份伪造，review P1）。</li>
 *   <li>approval 归属校验：approvalId 必须属于该 workflowId，否则 400（防跨工作流审批）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/workflows/{workflowId}/approvals")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final WorkflowExecutionService workflowExecutionService;
    private final CheckpointManager checkpointManager;
    private final WorkflowOwnershipChecker ownershipChecker;
    private final AdminApiKeys adminKeys;

    public ApprovalController(WorkflowExecutionService workflowExecutionService,
                              CheckpointManager checkpointManager,
                              WorkflowOwnershipChecker ownershipChecker,
                              @Value("${agentflow.admin.api-keys:}") String adminKeysCsv) {
        this.workflowExecutionService = workflowExecutionService;
        this.checkpointManager = checkpointManager;
        this.ownershipChecker = ownershipChecker;
        this.adminKeys = AdminApiKeys.from(adminKeysCsv);
    }

    // ──────────────────────── GET /pending ────────────────────────

    /** GET /api/workflows/{wfId}/approvals/pending — 待批列表（精简投影，不下发敏感载荷）。 */
    @GetMapping("/pending")
    public ResponseEntity<List<ApprovalView>> pending(
            @PathVariable String workflowId, HttpServletRequest httpRequest) {
        String callerId = WorkflowOwnershipChecker.callerIdFrom(httpRequest);
        if (!canAccess(workflowId, callerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<ApprovalView> views = checkpointManager.findPendingApprovals(workflowId).stream()
                .map(ApprovalView::of).toList();
        return ResponseEntity.ok(views);
    }

    // ──────────────────── POST /{approvalId}（决策） ────────────────────

    /** POST 决策请求体：<b>仅</b> {@code decision}——decidedBy 由服务端推导，客户端传入忽略。 */
    record DecisionRequest(String decision) {}

    /** POST /api/workflows/{wfId}/approvals/{approvalId} — 提交 APPROVE/REJECT 决策并续跑工作流。 */
    @PostMapping("/{approvalId}")
    public ResponseEntity<DecisionResponse> decide(
            @PathVariable String workflowId,
            @PathVariable String approvalId,
            @RequestBody DecisionRequest request,
            HttpServletRequest httpRequest) {

        String callerId = WorkflowOwnershipChecker.callerIdFrom(httpRequest);
        if (!canAccess(workflowId, callerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // 请求体校验
        if (request == null || request.decision() == null || request.decision().isBlank()) {
            return ResponseEntity.badRequest().body(new DecisionResponse("INVALID_DECISION",
                    "请求体需含 decision（APPROVE/REJECT）"));
        }

        // approval 归属校验：必须属于该 workflow（防跨工作流审批）
        ApprovalRequest approval = checkpointManager.findApprovalById(approvalId).orElse(null);
        if (approval == null || !workflowId.equals(approval.workflowId())) {
            return ResponseEntity.badRequest().body(new DecisionResponse("APPROVAL_NOT_FOUND",
                    "审批单不存在或不属于该工作流: " + approvalId));
        }

        ApprovalDecision decision;
        try {
            decision = ApprovalDecision.valueOf(request.decision().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new DecisionResponse("INVALID_DECISION",
                    "decision 需为 APPROVE 或 REJECT"));
        }

        // 服务端推导 decidedBy（忽略客户端请求体里任何 decidedBy，防伪造审批身份）
        String decidedBy = callerId;
        String wfName = checkpointManager.findWorkflowName(workflowId).orElse("unknown");
        String wfVersion = checkpointManager.findVersion(workflowId).orElse("1.0");
        com.agentflow.engine.checkpoint.WorkflowStatus status =
                workflowExecutionService.resumeAfterApproval(workflowId, wfName, wfVersion,
                        approvalId, decision, decidedBy);

        log.info("审批决策 wf={} approval={} decision={} by={} → status={}",
                workflowId, approvalId, decision, maskCaller(decidedBy), status);
        return ResponseEntity.ok(new DecisionResponse(decision.name(), status.name()));
    }

    // ──────────────────────────── 辅助 ────────────────────────────

    /** 访问控制：创建者（ownership）或 admin 可查/批（审批人 = 有权访问该工作流的外呼方）。 */
    private boolean canAccess(String workflowId, String callerId) {
        if (callerId == null) {
            return false;
        }
        try {
            ownershipChecker.requireOwnership(workflowId, callerId);
            return true;
        } catch (OwnershipException e) {
            return adminKeys.isAdmin(callerId);
        }
    }

    private static String maskCaller(String id) {
        return id == null ? "null" : id.substring(0, Math.min(8, id.length())) + "...";
    }

    // ──────────────────────────── 投影 DTOs ────────────────────────────

    /**
     * 待批审批的<b>精简投影</b>（U6）：只暴露给审批人必要字段，requestPayload/contextSnapshot
     * 仅存库用于恢复、不下发 API（review P2：防敏感载荷旁路泄漏）。
     */
    public record ApprovalView(
            String approvalId,
            String nodeId,
            String description,
            ApprovalStatus status,
            Instant createdAt
    ) {
        static ApprovalView of(ApprovalRequest r) {
            return new ApprovalView(r.approvalId(), r.nodeId(), r.description(), r.status(), r.createdAt());
        }
    }

    /** POST 决策响应：回显决策 + 后续工作流状态。 */
    public record DecisionResponse(String decision, String workflowStatus) {}
}
