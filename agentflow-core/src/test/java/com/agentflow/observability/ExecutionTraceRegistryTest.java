package com.agentflow.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExecutionTraceRegistry 单元测试（U7，KTD-2）。
 * 覆盖 register/snapshot/get/remove/noop。
 */
class ExecutionTraceRegistryTest {

    @Test
    @DisplayName("register 创建 trace 并可 get/snapshot")
    void registerCreatesAndGettable() {
        ExecutionTraceRegistry reg = new ExecutionTraceRegistry();
        ExecutionTrace trace = reg.register("wf-1");
        assertThat(trace).isNotNull();
        assertThat(trace.workflowId()).isEqualTo("wf-1");
        assertThat(reg.get("wf-1")).isSameAs(trace);
        assertThat(reg.snapshot("wf-1").workflowId()).isEqualTo("wf-1");
    }

    @Test
    @DisplayName("register 同 workflowId 幂等（返回同一实例）")
    void registerIdempotent() {
        ExecutionTraceRegistry reg = new ExecutionTraceRegistry();
        ExecutionTrace t1 = reg.register("wf-1");
        ExecutionTrace t2 = reg.register("wf-1");
        assertThat(t1).isSameAs(t2);
    }

    @Test
    @DisplayName("register null/blank workflowId 返回 null 不注册")
    void registerNullReturnsNull() {
        ExecutionTraceRegistry reg = new ExecutionTraceRegistry();
        assertThat(reg.register(null)).isNull();
        assertThat(reg.register("")).isNull();
        assertThat(reg.register("  ")).isNull();
        assertThat(reg.workflowIds()).isEmpty();
    }

    @Test
    @DisplayName("snapshot 未注册 workflowId 返回 null")
    void snapshotUnregisteredReturnsNull() {
        ExecutionTraceRegistry reg = new ExecutionTraceRegistry();
        assertThat(reg.snapshot("nope")).isNull();
    }

    @Test
    @DisplayName("remove 清理 trace")
    void remove() {
        ExecutionTraceRegistry reg = new ExecutionTraceRegistry();
        reg.register("wf-1");
        reg.remove("wf-1");
        assertThat(reg.get("wf-1")).isNull();
    }

    @Test
    @DisplayName("noop 实例 register/snapshot 永远返回 null")
    void noopInstance() {
        ExecutionTraceRegistry noop = ExecutionTraceRegistry.noop();
        assertThat(noop.register("wf-1")).isNull();
        assertThat(noop.snapshot("wf-1")).isNull();
    }

    @Test
    @DisplayName("并发 register 不同 workflowId 线程安全")
    void concurrentRegisterDifferentWorkflows() throws Exception {
        ExecutionTraceRegistry reg = new ExecutionTraceRegistry();
        int n = 50;
        java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final String wfId = "wf-" + i;
            exec.submit(() -> {
                try {
                    ExecutionTrace t = reg.register(wfId);
                    t.addNode(new NodeTrace("N", "a"));
                    t.markCompleted(ExecutionTrace.Status.COMPLETED);
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        exec.shutdownNow();
        assertThat(reg.workflowIds()).hasSize(n);
    }
}
