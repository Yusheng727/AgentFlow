package com.agentflow.api;

import com.agentflow.observability.ExecutionTrace;
import com.agentflow.observability.NodeTrace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DiagnosisService 测试（plan U6 Test scenarios：5 类问题识别）。
 */
class DiagnosisServiceTest {

    private final DiagnosisService service = new DiagnosisService();

    @Test
    @DisplayName("识别连续超时：FAILED + error 含 'timeout'")
    void detectsTimeoutFailures() {
        NodeTrace t1 = buildNode("A", "agent-a", NodeTrace.Status.SUCCESS, "ok", 0, 0);
        NodeTrace t2 = buildFailed("B", "agent-b", "java.util.concurrent.TimeoutException: call timed out");
        ExecutionTrace.Snapshot trace = snapshot(List.of(t1, t2));

        var report = service.diagnose(trace);

        assertThat(report.failedNodes()).isEqualTo(1);
        assertThat(report.findings()).anyMatch(f ->
                f.problemType().equals("连续超时") && f.nodeId().equals("B"));
    }

    @Test
    @DisplayName("识别 Token 异常消耗：某节点 token > 均值 ×3")
    void detectsTokenAnomaly() {
        NodeTrace normal1 = buildNode("A", "a", NodeTrace.Status.SUCCESS, "ok", 50, 50);
        NodeTrace normal2 = buildNode("B", "b", NodeTrace.Status.SUCCESS, "ok", 60, 40);
        NodeTrace abnormal = buildNode("C", "c", NodeTrace.Status.SUCCESS, "ok", 500, 500);
        ExecutionTrace.Snapshot trace = snapshot(List.of(normal1, normal2, abnormal));

        var report = service.diagnose(trace);

        assertThat(report.findings()).anyMatch(f ->
                f.problemType().equals("Token 异常消耗") && f.nodeId().equals("C"));
    }

    @Test
    @DisplayName("识别 SpEL 解析失败：error 含 'SpelEvaluation'")
    void detectsSpelFailure() {
        NodeTrace ok = buildNode("A", "a", NodeTrace.Status.SUCCESS, "ok", 0, 0);
        NodeTrace failed = buildFailed("B", "b", "SpelEvaluationException: ... T() not allowed");
        ExecutionTrace.Snapshot trace = snapshot(List.of(ok, failed));

        var report = service.diagnose(trace);

        assertThat(report.findings()).anyMatch(f -> f.problemType().equals("SpEL 解析失败"));
    }

    @Test
    @DisplayName("识别 Channel 缺失：error 含 'channel'")
    void detectsMissingChannel() {
        NodeTrace failed = buildFailed("A", "a", "channel 'missing' not found in context");
        ExecutionTrace.Snapshot trace = snapshot(List.of(failed));

        var report = service.diagnose(trace);

        assertThat(report.findings()).anyMatch(f -> f.problemType().equals("Channel 缺失"));
    }

    @Test
    @DisplayName("识别节点重复执行：同一 nodeId 出现 > 1 次 SUCCESS")
    void detectsDuplicateNodes() {
        NodeTrace t1 = buildNode("A", "a", NodeTrace.Status.SUCCESS, "ok", 10, 10);
        NodeTrace t2 = buildNode("A", "a", NodeTrace.Status.SUCCESS, "ok2", 15, 10);
        ExecutionTrace.Snapshot trace = snapshot(List.of(t1, t2));

        var report = service.diagnose(trace);

        assertThat(report.findings()).anyMatch(f ->
                f.problemType().equals("节点重复执行") && f.nodeId().equals("A"));
    }

    @Test
    @DisplayName("无问题的工作流 → 空 findings")
    void cleanWorkflowNoFindings() {
        NodeTrace t1 = buildNode("A", "a", NodeTrace.Status.SUCCESS, "ok", 30, 20);
        NodeTrace t2 = buildNode("B", "b", NodeTrace.Status.SUCCESS, "ok", 40, 30);
        ExecutionTrace.Snapshot trace = snapshot(List.of(t1, t2));

        var report = service.diagnose(trace);

        assertThat(report.totalNodes()).isEqualTo(2);
        assertThat(report.failedNodes()).isZero();
        assertThat(report.findings()).isEmpty();
    }

    private NodeTrace buildNode(String nodeId, String agentName, NodeTrace.Status status,
                                 String summary, long promptTokens, long completionTokens) {
        NodeTrace n = new NodeTrace(nodeId, agentName);
        if (status == NodeTrace.Status.SUCCESS) {
            n.succeed(summary, promptTokens, completionTokens);
        }
        return n;
    }

    private NodeTrace buildFailed(String nodeId, String agentName, String error) {
        NodeTrace n = new NodeTrace(nodeId, agentName);
        n.fail(error);
        return n;
    }

    private ExecutionTrace.Snapshot snapshot(List<NodeTrace> nodes) {
        return new ExecutionTrace.Snapshot("test-wf", null, null,
                nodes.stream().anyMatch(n -> n.status() == NodeTrace.Status.FAILED)
                        ? ExecutionTrace.Status.FAILED : ExecutionTrace.Status.COMPLETED,
                nodes, nodes.stream().mapToLong(NodeTrace::totalTokens).sum());
    }
}
