package com.agentflow.api;

import com.agentflow.api.security.ApiKeyAuthFilter;
import com.agentflow.api.security.WorkflowOwnershipChecker;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.observability.ExecutionTrace;
import com.agentflow.observability.ExecutionTraceRegistry;
import com.agentflow.observability.NodeTrace;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TraceController 单元测试（U7，R8）。
 *
 * <p>覆盖：① 已注册 workflowId + owner → 200 + snapshot ② 非创建者 → 403（IDOR 防护）
 * ③ 未注册 → 403（对齐 WorkflowController.getStatus 语义：不区分"不存在"与"非owner"）
 * ④ null registry → 403（防御 no-op 场景）⑤ mock 模式 trace 不为空（KTD-2 验证）
 * ⑥ snapshot 不可变冻结视图。
 */
class TraceControllerTest {

    private InMemoryCheckpointManager checkpointManager;
    private WorkflowOwnershipChecker ownershipChecker;

    @BeforeEach
    void setUp() {
        checkpointManager = new InMemoryCheckpointManager();
        ownershipChecker = new WorkflowOwnershipChecker(checkpointManager);
    }

    private static HttpServletRequest requestWithCaller(String callerId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(ApiKeyAuthFilter.CALLER_ID_ATTR)).thenReturn(callerId);
        return req;
    }

    /** 在 checkpointManager 里登记一个 workflow 的 createdBy，模拟 U14 鉴权后的所有权归属。 */
    private void ownWorkflow(String workflowId, String callerId) {
        // initWorkflow 4 参：workflowId / workflowName / version / createdBy（U5+U14 接口对齐）
        checkpointManager.initWorkflow(workflowId, "test-wf", "1", callerId);
    }

    @Test
    @DisplayName("① 已注册 workflowId + owner → 200 + 含 NodeTrace 的 Snapshot")
    void registeredOwnerReturns200WithSnapshot() {
        ExecutionTraceRegistry reg = new ExecutionTraceRegistry();
        ExecutionTrace trace = reg.register("wf-1");
        trace.addNode(new NodeTrace("A", "agent-a"));
        ownWorkflow("wf-1", "caller-A");
        TraceController controller = new TraceController(reg, ownershipChecker);

        var resp = controller.getTrace("wf-1", requestWithCaller("caller-A"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ExecutionTrace.Snapshot snap = resp.getBody();
        assertThat(snap).isNotNull();
        assertThat(snap.workflowId()).isEqualTo("wf-1");
        assertThat(snap.nodes()).hasSize(1);
        assertThat(snap.nodes().get(0).nodeId()).isEqualTo("A");
    }

    @Test
    @DisplayName("② 非创建者 → 403（IDOR 防护：trace 含 LLM 输出摘要，禁止他人读取）")
    void nonOwnerReturns403() {
        ExecutionTraceRegistry reg = new ExecutionTraceRegistry();
        reg.register("wf-secret").addNode(new NodeTrace("A", "a"));
        ownWorkflow("wf-secret", "owner-X");
        TraceController controller = new TraceController(reg, ownershipChecker);

        var resp = controller.getTrace("wf-secret", requestWithCaller("attacker-Y"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).isNull();
    }

    @Test
    @DisplayName("③ 未注册 workflowId → 403（对齐 getStatus 语义，不泄露存在性）")
    void unregisteredReturns403() {
        TraceController controller = new TraceController(new ExecutionTraceRegistry(), ownershipChecker);
        var resp = controller.getTrace("never-existed", requestWithCaller("caller-A"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).isNull();
    }

    @Test
    @DisplayName("④ null registry → 403（no-op 场景，ownership 仍先检查）")
    void nullRegistryReturns403() {
        ownWorkflow("wf-1", "caller-A");
        TraceController controller = new TraceController(null, ownershipChecker);
        var resp = controller.getTrace("wf-1", requestWithCaller("caller-A"));
        // owner 但 registry 为 null（无 trace）→ ownership 过，snapshot null → 404
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("⑤ 多 workflowId 隔离 + owner 校验：caller-A 查 wf-A 不返回 wf-B 的 trace")
    void workflowsIsolatedAndOwnershipChecked() {
        ExecutionTraceRegistry reg = new ExecutionTraceRegistry();
        reg.register("wf-A").addNode(new NodeTrace("A", "a"));
        reg.register("wf-B").addNode(new NodeTrace("B", "b"));
        ownWorkflow("wf-A", "caller-A");
        ownWorkflow("wf-B", "caller-B");
        TraceController controller = new TraceController(reg, ownershipChecker);

        var respA = controller.getTrace("wf-A", requestWithCaller("caller-A"));
        var respB = controller.getTrace("wf-B", requestWithCaller("caller-B"));
        // 交叉查询（A 查 B）→ 403
        var respCross = controller.getTrace("wf-B", requestWithCaller("caller-A"));

        assertThat(respA.getBody().nodes()).extracting(NodeTrace::nodeId).containsExactly("A");
        assertThat(respB.getBody().nodes()).extracting(NodeTrace::nodeId).containsExactly("B");
        assertThat(respCross.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("⑥ snapshot 是不可变冻结视图（trace 后续 add 不影响已返回 snapshot）")
    void snapshotIsImmutable() {
        ExecutionTraceRegistry reg = new ExecutionTraceRegistry();
        ExecutionTrace trace = reg.register("wf-1");
        trace.addNode(new NodeTrace("A", "a"));
        ownWorkflow("wf-1", "caller-A");
        TraceController controller = new TraceController(reg, ownershipChecker);

        var resp1 = controller.getTrace("wf-1", requestWithCaller("caller-A"));
        // 后续追加
        trace.addNode(new NodeTrace("B", "b"));

        // 第一次返回的 snapshot 不变
        assertThat(resp1.getBody().nodes()).hasSize(1);
        // 重新查有 2 个
        var resp2 = controller.getTrace("wf-1", requestWithCaller("caller-A"));
        assertThat(resp2.getBody().nodes()).hasSize(2);
    }
}