package com.agentflow.observability;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * AgentFlowMetrics 单元测试（U7，R9）。
 *
 * <p>覆盖 plan U7-U3 的 5 个 Test scenarios：① 5 指标正确注册 ② 成本自动计算
 * ③ mock 模式 token=0 不记 token（但可记成本 0）④ 预算超限 Counter 触发 ⑤ null registry no-op。
 */
class AgentFlowMetricsTest {

    @Test
    @DisplayName("① 5 指标正确注册：workflow.executed / node.duration / tokens.consumed / cost.estimated / cost.budget_exceeded")
    void fiveMetricsRegistered() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry);

        // 触发各指标
        metrics.recordWorkflowExecuted(AgentFlowMetrics.STATUS_SUCCESS);
        metrics.recordWorkflowExecuted(AgentFlowMetrics.STATUS_FAILED);
        metrics.recordNodeDuration("finance-agent", 1_000_000L);
        metrics.recordTokens("finance-agent", "gpt-4o", 100, 50);

        // ① workflow.executed（status tag 分 success/failed）
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_EXECUTED,
                "status", "success").count()).isEqualTo(1.0);
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_EXECUTED,
                "status", "failed").count()).isEqualTo(1.0);

        // ② node.duration（agent tag）
        Timer timer = registry.find(AgentFlowMetrics.NODE_DURATION).tag("agent", "finance-agent").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS))
                .isGreaterThanOrEqualTo(1_000_000L);
        // ②.5 node.duration 开 percentile histogram：暴露 .histogram 子 meter
        //（供 Grafana node.duration 面板 histogram_quantile 算 P50/P95/P99）
        assertThat(registry.find(AgentFlowMetrics.NODE_DURATION + ".histogram")).isNotNull();

        // ③ tokens.consumed（agent+model tag）= 150 total
        assertThat(registry.counter(AgentFlowMetrics.TOKENS_CONSUMED,
                "agent", "finance-agent", "model", "gpt-4o").count()).isEqualTo(150.0);

        // ④ cost.estimated（model tag）> 0
        double cost = registry.counter(AgentFlowMetrics.WORKFLOW_COST_ESTIMATED, "model", "gpt-4o").count();
        assertThat(cost).isGreaterThan(0.0);
        // gpt-4o: 100/1M × $2.5 + 50/1M × $10 = 0.00025 + 0.0005 = 0.00075
        assertThat(cost).isCloseTo(0.00075, within(1e-9));

        // ⑤ budget_exceeded 未触发（未调 checkBudget）
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("② 成本自动计算（KTD-3 单一真相源）：recordTokens 返回值与 CostCalculator 一致")
    void costAutoCalculatedConsistentWithCostCalculator() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CostCalculator calc = new CostCalculator();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry, calc);

        double returnedCost = metrics.recordTokens("a", "gpt-4o", 1_000_000L, 1_000_000L);
        double directCost = calc.cost("gpt-4o", 1_000_000L, 1_000_000L);

        assertThat(returnedCost).isCloseTo(directCost, within(1e-9));
        assertThat(returnedCost).isCloseTo(12.50, within(1e-9));
        // Counter 累加 = 12.50
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_ESTIMATED, "model", "gpt-4o").count())
                .isCloseTo(12.50, within(1e-9));
    }

    @Test
    @DisplayName("③ 多次 recordTokens 累加（不覆盖）")
    void accumulatesAcrossCalls() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry);

        metrics.recordTokens("a", "gpt-4o-mini", 100, 100);  // cost = 100/1M×0.15 + 100/1M×0.6 = 0.000075
        metrics.recordTokens("a", "gpt-4o-mini", 200, 200);  // cost = 200/1M×0.15 + 200/1M×0.6 = 0.00015

        // token 累加 = 600
        assertThat(registry.counter(AgentFlowMetrics.TOKENS_CONSUMED,
                "agent", "a", "model", "gpt-4o-mini").count()).isEqualTo(600.0);
        // 成本累加 = 0.000225
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_ESTIMATED, "model", "gpt-4o-mini").count())
                .isCloseTo(0.000225, within(1e-9));
    }

    @Test
    @DisplayName("④ 预算超限 Counter 触发：累计成本 > threshold → budget_exceeded++ 并返回 true")
    void budgetExceededTriggers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry);

        // 累加 $12.50 成本
        metrics.recordTokens("a", "gpt-4o", 1_000_000L, 1_000_000L);
        // threshold $10 → 已超限
        boolean exceeded = metrics.checkBudget(10.0);
        assertThat(exceeded).isTrue();
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isEqualTo(1.0);

        // 未超限场景
        boolean notExceeded = metrics.checkBudget(100.0);
        assertThat(notExceeded).isFalse();
        // Counter 不应再增（未超限不记）
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("④ totalCost 汇总所有 model 的 cost Counter")
    void totalCostSumsAcrossModels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry);

        metrics.recordTokens("a", "gpt-4o", 1_000_000L, 1_000_000L);        // $12.50
        metrics.recordTokens("a", "gpt-4o-mini", 1_000_000L, 1_000_000L);   // $0.75

        assertThat(metrics.totalCost()).isCloseTo(13.25, within(1e-9));
    }

    @Test
    @DisplayName("⑤ null MeterRegistry → 所有 record 方法 no-op 不抛")
    void nullRegistryNoOp() {
        AgentFlowMetrics metrics = new AgentFlowMetrics(null);

        // 不抛
        metrics.recordWorkflowExecuted(AgentFlowMetrics.STATUS_SUCCESS);
        metrics.recordNodeDuration("a", 100L);
        double cost = metrics.recordTokens("a", "gpt-4o", 100, 100);
        // recordTokens 仍返回成本（registry null 但 costCalculator 不 null）
        assertThat(cost).isGreaterThan(0.0);
        // checkBudget 在 null registry 下返回 false
        assertThat(metrics.checkBudget(0.0)).isFalse();
        // totalCost 在 null registry 下返回 0
        assertThat(metrics.totalCost()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("recordCost 仅记成本不记 token（避免 advisor 双计）")
    void recordCostOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry);

        double cost = metrics.recordCost("gpt-4o", 1_000_000L, 1_000_000L);
        assertThat(cost).isCloseTo(12.50, within(1e-9));

        // token counter 不应被记
        assertThat(registry.find(AgentFlowMetrics.TOKENS_CONSUMED).counter()).isNull();
        // cost counter 应被记
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_ESTIMATED, "model", "gpt-4o").count())
                .isCloseTo(12.50, within(1e-9));
    }

    @Test
    @DisplayName("recordNodeDuration Timer 用 Duration 转换（兼容 Micrometer API）")
    void nodeDurationTimerRecord() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry);

        metrics.recordNodeDuration("x", 5_000_000L);  // 5ms in nanos
        Timer t = registry.find(AgentFlowMetrics.NODE_DURATION).tag("agent", "x").timer();
        assertThat(t).isNotNull();
        assertThat(t.count()).isEqualTo(1L);
        Duration d = Duration.ofNanos((long) t.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS));
        assertThat(d.toMillis()).isGreaterThanOrEqualTo(5L);
    }

    @Test
    @DisplayName("status tag 用 unknown 兜底 null")
    void nullStatusTaggedUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry);
        metrics.recordWorkflowExecuted(null);
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_EXECUTED, "status", "unknown").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("null agentName / model → tag 用 unknown")
    void nullAgentModelTaggedUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry);

        metrics.recordTokens(null, null, 10, 10);
        assertThat(registry.counter(AgentFlowMetrics.TOKENS_CONSUMED,
                "agent", "unknown", "model", "unknown").count()).isEqualTo(20.0);
        // null model 走 fallback 单价
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_ESTIMATED, "model", "unknown").count())
                .isGreaterThan(0.0);
    }

    @Test
    @DisplayName("recordBudget（C1）：per-workflow 预算首次超限触发 budget_exceeded 一次（edge-triggered）")
    void recordBudgetEdgeTriggeredFiresOnce() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry);
        WorkflowBudget budget = new WorkflowBudget(null, 0.0000001); // 极小成本预算

        // 第一次 record：成本 ≈ 0.0000135 > 预算 → 超限并触发一次
        metrics.recordBudget(budget, "gpt-4o", 10, 20);
        assertThat(budget.isExceeded()).isTrue();
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isEqualTo(1.0);

        // 后续 record 不再触发（edge-triggered，事件数 ≠ 节点数）
        metrics.recordBudget(budget, "gpt-4o", 10, 20);
        metrics.recordBudget(budget, "gpt-4o", 10, 20);
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordBudget（C1）：预算未超限 / budget 为 null → 不触发 budget_exceeded")
    void recordBudgetWithinLimitAndNullBudget() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry);
        WorkflowBudget big = new WorkflowBudget(1_000_000L, 100.0);

        metrics.recordBudget(big, "gpt-4o", 10, 20);
        assertThat(big.isExceeded()).isFalse();
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isZero();

        // null budget → no-op 不抛（真实路径未声明预算时安全通过）
        metrics.recordBudget(null, "gpt-4o", 10, 20);
    }

    @Test
    @DisplayName("recordBudget（C1）：仅预算记账，不写 token/cost counter（供 Spring advisor 路径防双计）")
    void recordBudgetDoesNotDoubleCountTokenCost() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry);
        WorkflowBudget budget = new WorkflowBudget(null, 100.0);

        metrics.recordBudget(budget, "gpt-4o", 1_000_000L, 1_000_000L);

        // 预算已累进（成本 $12.50）
        assertThat(budget.cost()).isCloseTo(12.50, within(1e-9));
        // 但 token/cost counter 不应被写（避免与 TokenCountingAdvisor 双计）
        assertThat(registry.find(AgentFlowMetrics.TOKENS_CONSUMED).counter()).isNull();
        assertThat(registry.find(AgentFlowMetrics.WORKFLOW_COST_ESTIMATED).counter()).isNull();
    }
}
