package com.agentflow.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;

/**
 * AgentFlow Micrometer 指标统一封装（U7，R9）。
 *
 * <p>注册 5 个指标（与 Grafana Dashboard JSON 对齐，U7-U4）：
 * <ol>
 *   <li>{@code agentflow.workflow.executed} (Counter, tag: status) — 工作流执行计数（success/failed）</li>
 *   <li>{@code agentflow.node.duration} (Timer, tag: agent) — 单节点执行耗时</li>
 *   <li>{@code agentflow.tokens.consumed} (Counter, tag: agent, model) — token 消耗
 *       <br><b>注意</b>：{@code TokenCountingAdvisor}（U3）已有同名 counter，本类提供统一 API。
 *       适配器侧 TokenCountingAdvisor 仍直写 MeterRegistry（保留 U3 行为），本类提供
 *       {@link #recordTokens} 供 mock 模式 / 非 advisor 路径补记 token，避免双计。</li>
 *   <li>{@code agentflow.workflow.cost.estimated} (Counter, tag: model) — 成本估算（USD，累加）</li>
 *   <li>{@code agentflow.workflow.cost.budget_exceeded} (Counter) — 预算超限事件计数</li>
 * </ol>
 *
 * <h3>设计约束（KTD-3 成本核算）</h3>
 * <p>{@link #recordTokens} 在记 token Counter 后调 {@link CostCalculator#cost} 算成本，
 * 累加到 cost Counter——不新建 advisor，{@code TokenCountingAdvisor} 的 after() 可委托本方法
 * （避免重复实现成本逻辑，单一真相源）。
 *
 * <h3>预算超限</h3>
 * <p>调用方在累加成本后调 {@link #checkBudget}：若累计成本 > threshold，自增 budget_exceeded Counter
 * 并返回 true。Counter 仅记事件次数（不记金额），金额通过 cost Counter 时序可查。
 *
 * <p>线程安全：依赖 Micrometer {@code Counter.increment}/{@code Timer.record} 的原子性。
 * MeterRegistry 可空（null = no-op，便于无 actuator 环境跑测试）。
 */
public final class AgentFlowMetrics {

    /** 指标名常量（与 Grafana Dashboard JSON 引用一致，U7-U4）。 */
    public static final String WORKFLOW_EXECUTED = "agentflow.workflow.executed";
    public static final String NODE_DURATION = "agentflow.node.duration";
    public static final String TOKENS_CONSUMED = "agentflow.tokens.consumed";
    public static final String WORKFLOW_COST_ESTIMATED = "agentflow.workflow.cost.estimated";
    public static final String WORKFLOW_COST_BUDGET_EXCEEDED = "agentflow.workflow.cost.budget_exceeded";

    /** 工作流执行状态 tag 值。 */
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";

    private final MeterRegistry meterRegistry;
    private final CostCalculator costCalculator;

    /**
     * @param meterRegistry   Micrometer registry（可空——空时所有 record 方法 no-op，便于无 actuator 环境跑测试）
     * @param costCalculator  成本核算器（可空——空时用默认单价表，但建议显式注入以便配置覆盖）
     */
    public AgentFlowMetrics(MeterRegistry meterRegistry, CostCalculator costCalculator) {
        this.meterRegistry = meterRegistry;
        this.costCalculator = costCalculator != null ? costCalculator : new CostCalculator();
    }

    /** 便捷构造：默认 CostCalculator。 */
    public AgentFlowMetrics(MeterRegistry meterRegistry) {
        this(meterRegistry, new CostCalculator());
    }

    // ──────────────────────────── 工作流执行计数 ────────────────────────────

    /** 记一次工作流执行（success/failed）。status 用 {@link #STATUS_SUCCESS}/{@link #STATUS_FAILED}。 */
    public void recordWorkflowExecuted(String status) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(WORKFLOW_EXECUTED, Tags.of("status", status == null ? "unknown" : status)).increment();
    }

    // ──────────────────────────── 节点耗时 ────────────────────────────

    /** 记单节点执行耗时（纳秒）。agentName 作为 tag。 */
    public void recordNodeDuration(String agentName, long durationNanos) {
        if (meterRegistry == null) {
            return;
        }
        Timer.builder(NODE_DURATION)
                .tags(Tags.of("agent", agentName == null || agentName.isBlank() ? "unknown" : agentName))
                .register(meterRegistry)
                .record(java.time.Duration.ofNanos(durationNanos));
    }

    // ──────────────────────────── Token + 成本 ────────────────────────────

    /**
     * 记一次 LLM 调用的 token 消耗 + 自动核算成本（KTD-3 单一真相源）。
     *
     * <p>记 {@code agentflow.tokens.consumed}{agent,model} += totalTokens，
     * 并调 {@link CostCalculator#cost} 算成本累加到 {@code agentflow.workflow.cost.estimated}{model}。
     *
     * <p><b>避免双计</b>：若 {@code TokenCountingAdvisor} 已直写 token Counter，请勿再调本方法记 token；
     * 仅需调 {@link #recordCost} 单独记成本。本方法供 mock 模式（无 advisor）或统一封装路径使用。
     *
     * @param agent            agent 名（tag）
     * @param model            模型名（tag + 成本查表 key）
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     * @return 本次调用成本（USD，double）；registry 为空时仍按成本表计算返回
     */
    public double recordTokens(String agent, String model, long promptTokens, long completionTokens) {
        long total = promptTokens + completionTokens;
        double cost = costCalculator.cost(model, promptTokens, completionTokens);
        if (meterRegistry != null) {
            String agentTag = agent == null || agent.isBlank() ? "unknown" : agent;
            String modelTag = model == null || model.isBlank() ? "unknown" : model;
            meterRegistry.counter(TOKENS_CONSUMED, Tags.of("agent", agentTag).and("model", modelTag))
                    .increment(total);
            meterRegistry.counter(WORKFLOW_COST_ESTIMATED, Tags.of("model", modelTag))
                    .increment(cost);
        }
        return cost;
    }

    /**
     * 仅记成本（不记 token，供 TokenCountingAdvisor 已记 token 后的补成本场景）。
     *
     * @param model            模型名
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     * @return 成本（USD）
     */
    public double recordCost(String model, long promptTokens, long completionTokens) {
        double cost = costCalculator.cost(model, promptTokens, completionTokens);
        if (meterRegistry != null) {
            String modelTag = model == null || model.isBlank() ? "unknown" : model;
            meterRegistry.counter(WORKFLOW_COST_ESTIMATED, Tags.of("model", modelTag))
                    .increment(cost);
        }
        return cost;
    }

    // ──────────────────────────── 预算超限 ────────────────────────────

    /**
     * 检查累计成本是否超预算。超过则自增 {@code budget_exceeded} Counter 并返回 true。
     *
     * <p>累计成本从 cost Counter（按 model tag 聚合）实时读取，无需调用方维护状态。
     *
     * @param thresholdUsd 预算阈值（USD）
     * @return true=已超限（并已记 Counter），false=未超限
     */
    public boolean checkBudget(double thresholdUsd) {
        if (meterRegistry == null) {
            return false;
        }
        double total = totalCost();
        if (total > thresholdUsd) {
            meterRegistry.counter(WORKFLOW_COST_BUDGET_EXCEEDED).increment();
            return true;
        }
        return false;
    }

    /** 当前累计估算成本（USD，所有 model 求和）。registry 为空返回 0。 */
    public double totalCost() {
        if (meterRegistry == null) {
            return 0.0;
        }
        return meterRegistry.find(WORKFLOW_COST_ESTIMATED).counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    /** 取成本核算器（供调用方查单价表 / 测试断言用）。 */
    public CostCalculator costCalculator() {
        return costCalculator;
    }

    /** MeterRegistry（可空）。 */
    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }
}
