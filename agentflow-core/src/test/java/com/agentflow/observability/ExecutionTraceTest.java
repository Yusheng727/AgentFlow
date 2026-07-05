package com.agentflow.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionTraceTest {

    @Test
    @DisplayName("新增节点 + markCompleted 状态流转")
    void lifecycle() {
        ExecutionTrace trace = new ExecutionTrace("wf-1");
        assertThat(trace.status()).isEqualTo(ExecutionTrace.Status.RUNNING);
        assertThat(trace.nodes()).isEmpty();

        NodeTrace n = new NodeTrace("A", "finance-agent");
        trace.addNode(n);
        n.succeed("风险低", 100, 50);

        trace.markCompleted(ExecutionTrace.Status.COMPLETED);
        assertThat(trace.status()).isEqualTo(ExecutionTrace.Status.COMPLETED);
        assertThat(trace.endTime()).isNotNull();
        assertThat(trace.nodes()).hasSize(1);
        assertThat(trace.totalTokens()).isEqualTo(150);
    }

    @Test
    @DisplayName("NodeTrace succeed/fail 状态 + duration")
    void nodeTraceSuccess() throws InterruptedException {
        NodeTrace n = new NodeTrace("A", "a");
        Thread.sleep(5);
        n.succeed("out", 10, 20);
        assertThat(n.status()).isEqualTo(NodeTrace.Status.SUCCESS);
        assertThat(n.totalTokens()).isEqualTo(30);
        assertThat(n.duration().toMillis()).isGreaterThanOrEqualTo(5);
        assertThat(n.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("NodeTrace fail 记录错误")
    void nodeTraceFail() {
        NodeTrace n = new NodeTrace("A", "a");
        n.fail("timeout");
        assertThat(n.status()).isEqualTo(NodeTrace.Status.FAILED);
        assertThat(n.error()).isEqualTo("timeout");
        assertThat(n.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("并发追加节点 trace 线程安全（多 VT 同时 add）")
    void concurrentAppend() throws InterruptedException {
        ExecutionTrace trace = new ExecutionTrace("wf-concurrent");
        int n = 50;
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger success = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            exec.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    done.countDown();
                    return;
                }
                NodeTrace node = new NodeTrace("N" + idx, "agent");
                trace.addNode(node);
                node.succeed("out", 1, 2);
                success.incrementAndGet();
                done.countDown();
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        exec.shutdownNow();

        assertThat(trace.nodes()).hasSize(n);
        assertThat(trace.totalTokens()).isEqualTo((long) n * 3);
        assertThat(trace.terminalNodeCount()).isEqualTo(n);
    }

    @Test
    @DisplayName("snapshot 返回不可变冻结视图")
    void snapshotIsImmutable() {
        ExecutionTrace trace = new ExecutionTrace("wf-snap");
        trace.addNode(new NodeTrace("A", "a"));
        var snap = trace.snapshot();
        assertThat(snap.nodes()).hasSize(1);
        // 追加不影响已快照
        trace.addNode(new NodeTrace("B", "b"));
        assertThat(snap.nodes()).hasSize(1);
        assertThat(trace.nodes()).hasSize(2);
    }

    @Test
    @DisplayName("addNode null 安全忽略")
    void addNodeNullIgnored() {
        ExecutionTrace trace = new ExecutionTrace("wf");
        trace.addNode(null);
        assertThat(trace.nodes()).isEmpty();
    }
}
