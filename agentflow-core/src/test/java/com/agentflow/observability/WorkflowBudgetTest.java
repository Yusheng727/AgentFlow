package com.agentflow.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

/**
 * WorkflowBudget 单元测试（R10 per-workflow 预算累加器 edge-triggered 语义）。
 *
 * <p>覆盖：token/成本双维度超限、首次 true 之后 false、构造拒绝负数、并发、空预算 no-op。
 */
class WorkflowBudgetTest {

    private static final long TOKEN_LIMIT = 1000;

    @Test
    @DisplayName("token 超限：首次 record 返回 true，之后返回 false")
    void tokenExceededFirstTimeTrue() {
        var b = new WorkflowBudget(TOKEN_LIMIT, null);
        assertThat(b.record(500, 0, 0)).isFalse(); // 500 <= 1000
        assertThat(b.record(400, 0, 0)).isFalse(); // 900 <= 1000
        assertThat(b.record(200, 0, 0)).isTrue();   // 1100 > 1000, 首次超限
        assertThat(b.record(0, 0, 0)).isFalse();     // 之后返回 false
        assertThat(b.isExceeded()).isTrue();
    }

    @Test
    @DisplayName("成本超限：首次 record 返回 true，之后 false")
    void costExceededFirstTimeTrue() {
        var b = new WorkflowBudget(null, 0.02);
        assertThat(b.record(0, 0, 0.015)).isFalse();
        assertThat(b.record(0, 0, 0.01)).isTrue();  // 0.025 > 0.02
        assertThat(b.record(0, 0, 0)).isFalse();
        assertThat(b.isExceeded()).isTrue();
    }

    @Test
    @DisplayName("双维度预算：任一超限即触发（首次 true，之后 false）")
    void eitherDimensionTriggers() {
        var b = new WorkflowBudget(100L, 0.01);
        assertThat(b.record(50, 0, 0.005)).isFalse();
        assertThat(b.record(60, 0, 0.004)).isTrue(); // token 110 > 100, 首次超限
        assertThat(b.record(0, 0, 0.01)).isFalse();  // 之后 false
    }

    @Test
    @DisplayName("两个预算都 null → record 永远返回 false（纯累加器）")
    void bothNullNeverExceeds() {
        var b = new WorkflowBudget(null, null);
        assertThat(b.record(999_999, 999_999, 999.0)).isFalse();
        assertThat(b.isExceeded()).isFalse();
    }

    @Test
    @DisplayName("构造：负数 budgetTokens 抛 IllegalArgumentException")
    void negativeTokensThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WorkflowBudget(-1L, null));
    }

    @Test
    @DisplayName("构造：负数 budgetCost 抛 IllegalArgumentException")
    void negativeCostThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WorkflowBudget(null, -0.01));
    }

    @Test
    @DisplayName("构造：NaN budgetCost 抛 IllegalArgumentException")
    void nanCostThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WorkflowBudget(null, Double.NaN));
    }

    @Test
    @DisplayName("构造：Infinite budgetCost 抛 IllegalArgumentException")
    void infiniteCostThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WorkflowBudget(null, Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("tokens()/cost() 正确反映累计值")
    void accumulatesCorrectly() {
        var b = new WorkflowBudget(null, null);
        b.record(100, 50, 1.5);
        assertThat(b.tokens()).isEqualTo(150);
        assertThat(b.cost()).isEqualTo(1.5, within(1e-9));
        b.record(30, 20, 0.5);
        assertThat(b.tokens()).isEqualTo(200);
        assertThat(b.cost()).isEqualTo(2.0, within(1e-9));
    }

    @Test
    @DisplayName("并发 record：累计值正确 + 超限事件恰好触发一次")
    void concurrentRecordExceededOnce() throws Exception {
        var budget = new WorkflowBudget(10L, null); // 很小的预算，确保多次超限
        int threads = 8;
        var latch = new CountDownLatch(threads);
        var executor = Executors.newFixedThreadPool(threads);
        AtomicInteger trueCount = new AtomicInteger(0);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.countDown();
                    latch.await();
                    if (budget.record(10, 0, 0)) {
                        trueCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                }
            });
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // 尽管 8 线程 × 10 token = 80 > 10，但应该只有 1 个线程拿到 true（edge-triggered）
        assertThat(trueCount.get()).isEqualTo(1);
        assertThat(budget.isExceeded()).isTrue();
        assertThat(budget.tokens()).isEqualTo(80L);
    }

    @Test
    @DisplayName("recordBudgetExceeded 通过 SimpleMeterRegistry 自增 counter")
    void recordBudgetExceededIncrementsCounter() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AgentFlowMetrics(registry);
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isZero();
        metrics.recordBudgetExceeded();
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isEqualTo(1.0);
        metrics.recordBudgetExceeded();
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("isActive() 正确反映是否有预算维度")
    void isActive() {
        assertThat(new WorkflowBudget(null, null).isActive()).isFalse();
        assertThat(new WorkflowBudget(100L, null).isActive()).isTrue();
        assertThat(new WorkflowBudget(null, 0.5).isActive()).isTrue();
        assertThat(new WorkflowBudget(100L, 0.5).isActive()).isTrue();
    }
}