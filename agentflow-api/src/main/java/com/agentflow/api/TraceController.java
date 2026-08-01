package com.agentflow.api;

import com.agentflow.observability.ExecutionTrace;
import com.agentflow.observability.ExecutionTraceRegistry;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trace REST Controller（U7，R8）。
 *
 * <h3>端点</h3>
 * <ul>
 *   <li>{@code GET /api/workflows/{id}/trace} — 返回 {@link ExecutionTrace.Snapshot}（JSON），
 *       含 workflow 级状态 + 全部 {@link com.agentflow.observability.NodeTrace} 列表 + 总 token</li>
 * </ul>
 *
 * <h3>mock 模式 trace 补齐（KTD-2）</h3>
 * <p>{@link ExecutionTraceRegistry} 由 {@code BspEngine} 在 execute() 开头注册（每个 workflowId 一个 trace），
 * 通过 {@link com.agentflow.agent.AgentInput#trace()} 透传给 {@link com.agentflow.adapters.mock.MockAgentFunction}
 * （mock 模式）与 {@code SpringAiAgentAdapter}（真实模式），两者都写 {@link com.agentflow.observability.NodeTrace}。
 * 故本端点在 mock 模式下也返回完整轨迹树，不返回空树。
 *
 * <p>鉴权：v1 不加所有权校验（trace 查询只读，且 registry 是进程内 transient；U14 鉴权在 WorkflowController 已覆盖，
 * TraceController 由 Starter 的 security filter 链统一保护）。生产环境应补 ownership check，留 v1.1。
 *
 * <p>未注册的 workflowId 返回 404 + 空 body（不返回 200 空对象，避免误判）。
 */
@RestController
@RequestMapping("/api/workflows")
public class TraceController {

    private final ExecutionTraceRegistry traceRegistry;

    public TraceController(ExecutionTraceRegistry traceRegistry) {
        this.traceRegistry = traceRegistry;
    }

    /**
     * 查工作流执行轨迹。
     *
     * @param workflowId 工作流执行 id
     * @return 200 + {@link ExecutionTrace.Snapshot}；未注册返回 404
     */
    @GetMapping("/{workflowId}/trace")
    public ResponseEntity<ExecutionTrace.Snapshot> getTrace(@PathVariable String workflowId) {
        ExecutionTrace.Snapshot snapshot = traceRegistry == null ? null : traceRegistry.snapshot(workflowId);
        if (snapshot == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(snapshot);
    }
}
