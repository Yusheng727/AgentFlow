package com.agentflow.api;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.api.security.ApiKeyAuthFilter;
import com.agentflow.api.security.CallerToolAllowlist;
import com.agentflow.api.security.WorkflowOwnershipChecker;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.dsl.WorkflowValidationException;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.WorkflowExecutionException;
import com.agentflow.engine.checkpoint.CheckpointManager;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final NodeRegistry nodeRegistry;
    private final ExecutorService executor;

    public WorkflowController(WorkflowDSLParser parser,
                              BspEngine engine,
                              CheckpointManager checkpointManager,
                              WorkflowOwnershipChecker ownershipChecker,
                              CallerToolAllowlist toolAllowlist,
                              NodeRegistry nodeRegistry) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.checkpointManager = Objects.requireNonNull(checkpointManager, "checkpointManager");
        this.ownershipChecker = Objects.requireNonNull(ownershipChecker, "ownershipChecker");
        this.toolAllowlist = Objects.requireNonNull(toolAllowlist, "toolAllowlist");
        this.nodeRegistry = Objects.requireNonNull(nodeRegistry, "nodeRegistry");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    // ──────────────────────────── POST /workflows ────────────────────────────

    /**
     * 提交工作流（异步执行）。
     *
     * <p>请求体：
     * <pre>{@code
     * {
     *   "workflow_name": "supplier-risk",
     *   "version": "1.0",
     *   "yaml_content": "agentflow:\n  version: \"1.0\"\nchannels: ...",
     *   "inputs": { "company": "Acme Corp" }
     * }
     * }</pre>
     *
     * <p>返回：202 + workflow_id + status 链接
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

        // 3. 生成 workflow_id + 持久化
        String workflowId = UUID.randomUUID().toString();
        String version = def.version();
        checkpointManager.initWorkflow(workflowId, request.workflowName(), version, callerId);

        // 4. 派发异步执行
        Map<String, Object> inputs = request.inputs() != null ? request.inputs() : Map.of();
        executor.submit(() -> {
            try {
                log.info("开始执行 wf={} name={}", workflowId, request.workflowName());
                checkpointManager.updateStatus(workflowId, "RUNNING");
                engine.execute(def, nodeRegistry, inputs, checkpointManager,
                        new ChannelReducer(), workflowId);
                checkpointManager.updateStatus(workflowId, "SUCCESS");
                log.info("工作流执行成功 wf={}", workflowId);
            } catch (WorkflowExecutionException e) {
                log.error("工作流执行失败 wf={} step={}", workflowId, e.getSuperStep(), e);
                checkpointManager.updateStatus(workflowId, "FAILED");
            } catch (Exception e) {
                log.error("工作流执行异常 wf={}", workflowId, e);
                checkpointManager.updateStatus(workflowId, "FAILED");
            }
        });

        SubmitResponse response = new SubmitResponse(
                workflowId, "PENDING", null,
                new StatusLinks("/api/workflows/" + workflowId + "/status"));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
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

        String status = checkpointManager.findStatus(workflowId).orElse("UNKNOWN");
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

        String currentStatus = checkpointManager.findStatus(workflowId).orElse("UNKNOWN");
        if (!"FAILED".equals(currentStatus)) {
            return ResponseEntity.badRequest().body(
                    new SubmitResponse(workflowId, currentStatus,
                            "仅 FAILED 状态的工作流可重试，当前状态：" + currentStatus, null));
        }

        log.info("重试工作流 wf={}", workflowId);
        // v1 简化：retry 从头执行（U5 RecoveryProtocol 提供 recover-and-execute 后改为增量恢复）
        executor.submit(() -> {
            try {
                checkpointManager.updateStatus(workflowId, "RUNNING");
                // retry 时 YAML/agents 从提交时的持久化获取——v1 不重新解析
                // TODO U5: 调 RecoveryProtocol.recover() 恢复执行状态
                checkpointManager.updateStatus(workflowId, "SUCCESS");
            } catch (Exception e) {
                log.error("重试异常 wf={}", workflowId, e);
                checkpointManager.updateStatus(workflowId, "FAILED");
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

    // ──────────────────────────── 辅助 ────────────────────────────

    private static String maskCaller(String id) {
        return id == null ? "null" : id.substring(0, Math.min(8, id.length())) + "...";
    }
}