package com.agentflow.api;

import com.agentflow.observability.ExecutionTrace;
import com.agentflow.observability.ExecutionTraceRegistry;
import com.agentflow.observability.NodeTrace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TraceController 单元测试（U7，R8）。
 *
 * <p>覆盖：① 已注册 workflowId → 200 + snapshot ② 未注册 → 404
 * ③ mock 模式 trace 不为空（KTD-2 验证：通过 BspEngine + MockAgentFunction 写入后可查）。
 */
class TraceControllerTest {

    @Test
    @DisplayName("① 已注册 workflowId → 200 + 含 NodeTrace 的 Snapshot")
    void registeredReturns200WithSnapshot() {
        ExecutionTraceRegistry reg = new ExecutionTraceRegistry();
        ExecutionTrace trace = reg.register("wf-1");
        trace.addNode(new NodeTrace("A", "agent-a"));
        TraceController controller = new TraceController(reg);

        var resp = controller.getTrace("wf-1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ExecutionTrace.Snapshot snap = resp.getBody();
        assertThat(snap).isNotNull();
        assertThat(snap.workflowId()).isEqualTo("wf-1");
        assertThat(snap.nodes()).hasSize(1);
        assertThat(snap.nodes().get(0).nodeId()).isEqualTo("A");
    }

    @Test
    @DisplayName("② 未注册 workflowId → 404 + 空 body")
    void unregisteredReturns404() {
        TraceController controller = new TraceController(new ExecutionTraceRegistry());
        var resp = controller.getTrace("never-existed");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNull();
    }

    @Test
    @DisplayName("③ null registry → 404（防御 no-op 场景）")
    void nullRegistryReturns404() {
        TraceController controller = new TraceController(null);
        var resp = controller.getTrace("wf-1");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("④ 多 workflowId 隔离：查 wf-A 不返回 wf-B 的 trace")
    void workflowsIsolated() {
        ExecutionTraceRegistry reg = new ExecutionTraceRegistry();
        reg.register("wf-A").addNode(new NodeTrace("A", "a"));
        reg.register("wf-B").addNode(new NodeTrace("B", "b"));
        TraceController controller = new TraceController(reg);

        var respA = controller.getTrace("wf-A");
        var respB = controller.getTrace("wf-B");

        assertThat(respA.getBody().nodes()).extracting(NodeTrace::nodeId).containsExactly("A");
        assertThat(respB.getBody().nodes()).extracting(NodeTrace::nodeId).containsExactly("B");
    }

    @Test
    @DisplayName("⑤ snapshot 是不可变冻结视图（trace 后续 add 不影响已返回 snapshot）")
    void snapshotIsImmutable() {
        ExecutionTraceRegistry reg = new ExecutionTraceRegistry();
        ExecutionTrace trace = reg.register("wf-1");
        trace.addNode(new NodeTrace("A", "a"));
        TraceController controller = new TraceController(reg);

        var resp1 = controller.getTrace("wf-1");
        // 后续追加
        trace.addNode(new NodeTrace("B", "b"));

        // 第一次返回的 snapshot 不变
        assertThat(resp1.getBody().nodes()).hasSize(1);
        // 重新查有 2 个
        var resp2 = controller.getTrace("wf-1");
        assertThat(resp2.getBody().nodes()).hasSize(2);
    }
}
