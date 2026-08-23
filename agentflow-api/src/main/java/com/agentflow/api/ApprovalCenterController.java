package com.agentflow.api;

import com.agentflow.api.security.ApiKeyAuthFilter;
import com.agentflow.api.security.WorkflowOwnershipChecker;
import com.agentflow.engine.checkpoint.ApprovalRequest;
import com.agentflow.engine.checkpoint.CheckpointManager;
import com.agentflow.engine.checkpoint.WorkflowExecutionRecord;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 审批中心聚合端点（U3）：跨工作流待批列表，供 UI「审批中心」Tab。
 *
 * <p>路由 {@code GET /api/approvals/pending}——与 {@link ApprovalController} 的 per-workflow
 * 端点（{@code /api/workflows/{wfId}/approvals/pending}）互补：审批中心需要「我（或全部）
 * 有哪些待批」的聚合视图，不先知道 workflowId 无从查起。
 *
 * <p><b>可见域</b>（对齐 ApprovalController 的门控语义）：
 * <ul>
 *   <li>非 admin：仅自己创建的工作流（{@code listByCreatedBy(caller)} 遍历，天然不泄漏他人审批存在性）</li>
 *   <li>admin（env {@code AGENTFLOW_ADMIN_API_KEYS}）：全部工作流（createdBy 不过滤）</li>
 * </ul>
 *
 * <p><b>投影纪律</b>：复用 {@link ApprovalController.ApprovalView} 的精简投影思路并加
 * {@code workflowId}（聚合视图需要归属信息）——<b>不下发</b> requestPayload/contextSnapshot
 * （review P2：防敏感载荷旁路泄漏）。
 *
 * <p><b>性能注</b>：N 工作流 N+1 查询（listByCreatedBy + 每工作流 findPendingApprovals）。
 * demo 规模可接受；SQL JOIN 单查询优化记 Deferred（plan U3 注）。
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalCenterController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalCenterController.class);

    private final CheckpointManager checkpointManager;
    private final WorkflowOwnershipChecker ownershipChecker;
    private final Set<String> adminHashes;

    public ApprovalCenterController(CheckpointManager checkpointManager,
                                    WorkflowOwnershipChecker ownershipChecker,
                                    @Value("${agentflow.admin.api-keys:}") String adminKeysCsv) {
        this.checkpointManager = checkpointManager;
        this.ownershipChecker = ownershipChecker;
        this.adminHashes = adminKeysCsv == null || adminKeysCsv.isBlank()
                ? Collections.emptySet()
                : hashKeys(adminKeysCsv);
    }

    /** GET /api/approvals/pending — 跨工作流待批聚合（精简投影，含 workflowId）。 */
    @GetMapping("/pending")
    public ResponseEntity<List<ApprovalCenterView>> pending(HttpServletRequest httpRequest) {
        String callerId = WorkflowOwnershipChecker.callerIdFrom(httpRequest);
        if (callerId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean admin = adminHashes.contains(callerId);
        // admin 传 null=不过滤（listByCreatedBy 的既有语义：空 createdBy 返回全部）
        List<WorkflowExecutionRecord> workflows =
                checkpointManager.listByCreatedBy(admin ? null : callerId);

        List<ApprovalCenterView> views = new ArrayList<>();
        for (WorkflowExecutionRecord wf : workflows) {
            for (ApprovalRequest r : checkpointManager.findPendingApprovals(wf.workflowId())) {
                views.add(ApprovalCenterView.of(r));
            }
        }
        log.debug("审批中心聚合 caller={} admin={} workflows={} pending={}",
                maskCaller(callerId), admin, workflows.size(), views.size());
        return ResponseEntity.ok(views);
    }

    private static Set<String> hashKeys(String csv) {
        Set<String> hashes = new LinkedHashSet<>();
        Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .forEach(k -> hashes.add(ApiKeyAuthFilter.sha256(k)));
        return Collections.unmodifiableSet(hashes);
    }

    private static String maskCaller(String id) {
        return id == null ? "null" : id.substring(0, Math.min(8, id.length())) + "...";
    }

    // ──────────────────────────── 投影 DTO ────────────────────────────

    /**
     * 审批中心聚合视图（U3）：{@link ApprovalController.ApprovalView} 精简投影 + {@code workflowId}
     * （跨工作流归属）。同样不下发 requestPayload/contextSnapshot。
     */
    public record ApprovalCenterView(
            String approvalId,
            String workflowId,
            String workflowName,
            String nodeId,
            String description,
            String status,
            java.time.Instant createdAt
    ) {
        static ApprovalCenterView of(ApprovalRequest r) {
            return new ApprovalCenterView(r.approvalId(), r.workflowId(), null, r.nodeId(),
                    r.description(), r.status().name(), r.createdAt());
        }
    }
}
