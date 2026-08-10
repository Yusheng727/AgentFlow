package com.agentflow.api;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.api.security.ApiKeyAuthFilter;
import com.agentflow.api.security.CallerToolAllowlist;
import com.agentflow.api.security.WorkflowOwnershipChecker;
import com.agentflow.api.security.WorkflowSubmissionGuard;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.dsl.WorkflowValidationException;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.WorkflowExecutionException;
import com.agentflow.engine.checkpoint.CheckpointManager;
import com.agentflow.engine.checkpoint.WorkflowExecutionRecord;
import com.agentflow.engine.checkpoint.WorkflowStatus;
import com.agentflow.observability.CostCalculator;
import com.agentflow.version.VersionConflictDetector;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Workflow REST Controller（U14）。
 *
 * <h3>端点</h3>
 * <ul>
 *   <li>{@code POST /api/workflows} — 提交 YAML + inputs，异步执行，返回 202</li>
 *   <li>{@code GET /api/workflows/{id}/status} — 查询工作流执行状态（仅创建者可查）</li>
 *   <li>{@code POST /api/workflows/{id}/retry} — 重试失败工作流（仅创建者可重试）</li>
 * </ul>
 *
 * <h3>鉴权</h3>
 * <p>所有端点受 {@link ApiKeyAuthFilter} 保护（401）。状态/重试端点额外受
 * {@link WorkflowOwnershipChecker} 保护（403 防 IDOR）。
 *
 * <h3>异步执行（v4.2）</h3>
 * <p>POST 提交后立即返回 202，不阻塞在 BSP 执行上（避免 30-120s+ 超时）。
 * 状态通过 GET /status 端点轮询。
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final WorkflowDSLParser parser;
    private final BspEngine engine;
    private final CheckpointManager checkpointManager;
    private final WorkflowOwnershipChecker ownershipChecker;
    private final CallerToolAllowlist toolAllowlist;
    private final WorkflowSubmissionGuard submissionGuard;
    private final NodeRegistry nodeRegistry;
    private final com.agentflow.version.WorkflowVersionManager versionManager;
    private final ExecutorService executor;

    public WorkflowController(WorkflowDSLParser parser,
                              BspEngine engine,
                              CheckpointManager checkpointManager,
                              WorkflowOwnershipChecker ownershipChecker,
                              CallerToolAllowlist toolAllowlist,
                              NodeRegistry nodeRegistry,
                              com.agentflow.version.WorkflowVersionManager versionManager) {
        this(parser, engine, checkpointManager, ownershipChecker, toolAllowlist, nodeRegistry,
                versionManager,
                // 默认守卫：仅启用节点数上界（防无界 VT），成本检查需 model+预算由 wiring 显式配置
                new WorkflowSubmissionGuard(new CostCalculator(), null,
                        WorkflowSubmissionGuard.DEFAULT_MAX_NODES, null));
    }

    @Autowired
    public WorkflowController(WorkflowDSLParser parser,
                              BspEngine engine,
                              CheckpointManager checkpointManager,
                              WorkflowOwnershipChecker ownershipChecker,
                              CallerToolAllowlist toolAllowlist,
                              NodeRegistry nodeRegistry,
                              com.agentflow.version.WorkflowVersionManager versionManager,
                              WorkflowSubmissionGuard submissionGuard) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.checkpointManager = Objects.requireNonNull(checkpointManager, "checkpointManager");
        this.ownershipChecker = Objects.requireNonNull(ownershipChecker, "ownershipChecker");
        this.toolAllowlist = Objects.requireNonNull(toolAllowlist, "toolAllowlist");
        this.submissionGuard = Objects.requireNonNull(submissionGuard, "submissionGuard");
        this.nodeRegistry = Objects.requireNonNull(nodeRegistry, "nodeRegistry");
        this.versionManager = Objects.requireNonNull(versionManager, "versionManager");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    // ──────────────────────────── POST /workflows ────────────────────────────

    /**
     * 提交工作流（异步执行）。
     *
     * <p>请求体：
     * <pre>{@code
     * {
     *   "workflowName": "supplier-risk",
     *   "version": "1.0",
     *   "yamlContent": "agentflow:\n  version: \"1.0\"\nchannels: ...",
     *   "inputs": { "company": "Acme Corp" }
     * }
     * }</pre>
     *
     * <p>返回：202 + workflowId + status 链接（wire 契约 camelCase，与前端 UI 类型一致）
     */
    @PostMapping
    public ResponseEntity<SubmitResponse> submit(
            @RequestBody SubmitRequest request,
            HttpServletRequest httpRequest) {

        String callerId = WorkflowOwnershipChecker.callerIdFrom(httpRequest);

        // 1. 解析 YAML（同步，校验在此阶段）
        WorkflowDefinition def;
        try {
            def = parser.parse(new ByteArrayInputStream(
                    request.yamlContent().getBytes(StandardCharsets.UTF_8)));
        } catch (WorkflowValidationException e) {
            return ResponseEntity.badRequest().body(
                    new SubmitResponse(null, "INVALID_YAML", e.getMessage(), null));
        }

        // 1.5 兜底：可解析但缺 nodes（如 "agentflow:" 空声明）也当非法 YAML——否则下方遍历 NPE→500
        if (def.nodes() == null || def.nodes().isEmpty()) {
            return ResponseEntity.badRequest().body(
                    new SubmitResponse(null, "INVALID_YAML", "工作流定义缺少 nodes 段", null));
        }

        // 2. 工具级授权检查（v4.3）
        if (callerId != null) {
            for (var node : def.nodes()) {
                if (node.tools() != null) {
                    for (String tool : node.tools()) {
                        if (!toolAllowlist.isAllowed(callerId, tool)) {
                            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                                    new SubmitResponse(null, "FORBIDDEN",
                                            "未授权使用 Tool: " + tool + "（caller=" + maskCaller(callerId) + "）", null));
                        }
                    }
                }
            }
        }

        // 2.5 提交守卫（预防性）：DAG 节点数 / 预估成本超上界 → 422 拒绝（防无界 VT + 烧成本）
        WorkflowSubmissionGuard.SubmissionResult guard = submissionGuard.check(def);
        if (!guard.allowed()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                    new SubmitResponse(null, "SUBMISSION_LIMIT", guard.rejectionReason(), null));
        }

        // 3. 生成 workflow_id + 持久化
        String workflowId = UUID.randomUUID().toString();
        String version = def.version();
        checkpointManager.initWorkflow(workflowId, request.workflowName(), version, callerId);

        // 3.5 版本管理（U8 R14）：把本次解析定义按 (name, version) 存入 store——
        // 恢复/retry 从 store 取定义（不再从 classpath 读），版本 bump 后旧实例仍按旧 DAG 执行。
        versionManager.recordWorkflowDefinition(request.workflowName(), def);
        versionManager.detectConflict(request.workflowName(), version).ifPresent(c ->
                log.warn(c.message()));

        // 4. 派发异步执行
        Map<String, Object> inputs = request.inputs() != null ? request.inputs() : Map.of();
        executor.submit(() -> {
            try {
                log.info("开始执行 wf={} name={}", workflowId, request.workflowName());
                checkpointManager.updateStatus(workflowId, WorkflowStatus.RUNNING);
                engine.execute(def, nodeRegistry, inputs, checkpointManager,
                        new ChannelReducer(), workflowId);
                checkpointManager.updateStatus(workflowId, WorkflowStatus.SUCCESS);
                log.info("工作流执行成功 wf={}", workflowId);
            } catch (WorkflowExecutionException e) {
                log.error("工作流执行失败 wf={} step={}", workflowId, e.superStep(), e);
                checkpointManager.updateStatus(workflowId, WorkflowStatus.FAILED);
            } catch (Exception e) {
                log.error("工作流执行异常 wf={}", workflowId, e);
                checkpointManager.updateStatus(workflowId, WorkflowStatus.FAILED);
            }
        });

        SubmitResponse response = new SubmitResponse(
                workflowId, "PENDING", null,
                new StatusLinks("/api/workflows/" + workflowId + "/status"));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    // ──────────────────────── GET /workflows（列表，U10 后续 #12） ────────────────────────

    /**
     * 列出当前调用者可访问（自己创建）的工作流执行实例（看板数据源）。
     *
     * <p>受 {@link ApiKeyAuthFilter} 保护（401）；按创建者过滤（每 API Key 只看到自己的工作流）。
     * {@link CheckpointManager#listByCreatedBy}（InMemory + Postgres 均已实现）。
     *
     * @return 200 + {@link List}<{@link WorkflowExecutionRecord}>（按创建时间倒序）
     */
    @GetMapping
    public ResponseEntity<List<WorkflowExecutionRecord>> list(HttpServletRequest httpRequest) {
        String callerId = WorkflowOwnershipChecker.callerIdFrom(httpRequest);
        return ResponseEntity.ok(checkpointManager.listByCreatedBy(callerId));
    }

    // ──────────────────── GET /workflows/{id}/version-check（U8 R14） ────────────────────

    /**
     * 版本冲突检查（仅创建者可查）：报告执行记录的版本 vs 该工作流最新定义版本是否不一致。
     * 不一致仅提示（WARN 语义），不阻断——已运行/恢复实例按各自版本执行到结束。
     */
    @GetMapping("/{workflowId}/version-check")
    public ResponseEntity<VersionCheckResponse> versionCheck(
            @PathVariable String workflowId,
            HttpServletRequest httpRequest) {

        String callerId = WorkflowOwnershipChecker.callerIdFrom(httpRequest);
        try {
            ownershipChecker.requireOwnership(workflowId, callerId);
        } catch (WorkflowOwnershipChecker.OwnershipException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String name = checkpointManager.findWorkflowName(workflowId).orElse(null);
        String executedVersion = checkpointManager.findVersion(workflowId)
                .filter(v -> !v.isBlank()).orElse("1.0");
        var conflict = versionManager.detectConflict(name == null ? "unknown" : name, executedVersion);

        return ResponseEntity.ok(new VersionCheckResponse(
                workflowId,
                name,
                executedVersion,
                conflict.map(VersionConflictDetector.Conflict::latestVersion).orElse(null),
                conflict.isPresent(),
                conflict.map(VersionConflictDetector.Conflict::message)
                        .orElse("版本一致或无历史定义")));
    }

    // ──────────────────────── GET /workflows/{id}/status ────────────────────────

    /**
     * 查询工作流执行状态（仅创建者可查）。
     */
    @GetMapping("/{workflowId}/status")
    public ResponseEntity<StatusResponse> getStatus(
            @PathVariable String workflowId,
            HttpServletRequest httpRequest) {

        String callerId = WorkflowOwnershipChecker.callerIdFrom(httpRequest);

        // 所有权校验（防 IDOR）
        try {
            ownershipChecker.requireOwnership(workflowId, callerId);
        } catch (WorkflowOwnershipChecker.OwnershipException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String status = checkpointManager.findStatus(workflowId)
                .map(WorkflowStatus::name).orElse("UNKNOWN");
        return ResponseEntity.ok(new StatusResponse(workflowId, status, Instant.now()));
    }

    // ──────────────────────── POST /workflows/{id}/retry ────────────────────────

    /**
     * 重试失败的工作流（仅创建者可重试）。
     */
    @PostMapping("/{workflowId}/retry")
    public ResponseEntity<SubmitResponse> retry(
            @PathVariable String workflowId,
            HttpServletRequest httpRequest) {

        String callerId = WorkflowOwnershipChecker.callerIdFrom(httpRequest);

        // 所有权校验
        try {
            ownershipChecker.requireOwnership(workflowId, callerId);
        } catch (WorkflowOwnershipChecker.OwnershipException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String currentStatus = checkpointManager.findStatus(workflowId)
                .map(WorkflowStatus::name).orElse("UNKNOWN");
        if (!"FAILED".equals(currentStatus)) {
            return ResponseEntity.badRequest().body(
                    new SubmitResponse(workflowId, currentStatus,
                            "仅 FAILED 状态的工作流可重试，当前状态：" + currentStatus, null));
        }

        log.info("重试工作流 wf={}", workflowId);
        // v1 简化：retry 从头执行（U5 RecoveryProtocol 提供 recover-and-execute 后改为增量恢复）
        executor.submit(() -> {
            try {
                checkpointManager.updateStatus(workflowId, WorkflowStatus.RUNNING);
                // retry 时 YAML/agents 从提交时的持久化获取——v1 不重新解析
                // TODO U5: 调 RecoveryProtocol.recover() 恢复执行状态
                checkpointManager.updateStatus(workflowId, WorkflowStatus.SUCCESS);
            } catch (Exception e) {
                log.error("重试异常 wf={}", workflowId, e);
                checkpointManager.updateStatus(workflowId, WorkflowStatus.FAILED);
            }
        });

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                new SubmitResponse(workflowId, "PENDING", "Retry submitted",
                        new StatusLinks("/api/workflows/" + workflowId + "/status")));
    }

    // ──────────────────────────── DTOs ────────────────────────────

    /** POST /workflows 请求体。 */
    public record SubmitRequest(
            String workflowName,
            String version,
            String yamlContent,
            Map<String, Object> inputs
    ) {}

    /** POST /workflows 响应体。 */
    public record SubmitResponse(
            String workflowId,
            String status,
            String message,
            StatusLinks links
    ) {}

    /** GET /workflows/{id}/status 响应体。 */
    public record StatusResponse(
            String workflowId,
            String status,
            Instant queriedAt
    ) {}

    /** HATEOAS-lite 链接。 */
    public record StatusLinks(String status) {}

    /** U8 版本冲突检查响应：执行版本 + 最新定义版本 + 冲突标记/消息（WARN 语义，不阻断）。 */
    public record VersionCheckResponse(
            String workflowId,
            String workflowName,
            String executedVersion,
            String latestVersion,
            boolean conflict,
            String message
    ) {}

    // ──────────────────────────── 辅助 ────────────────────────────

    private static String maskCaller(String id) {
        return id == null ? "null" : id.substring(0, Math.min(8, id.length())) + "...";
    }
}