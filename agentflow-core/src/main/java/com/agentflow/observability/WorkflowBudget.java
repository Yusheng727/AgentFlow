package com.agentflow.observability;

/**
 * per-workflow 预算累加器（R10，OQ `budget 告警无 threshold source`）。
 *
 * <p>把 R10 的 {@code budget_exceeded} 语义从"全局 mock 阈值"升级为<b>每个工作流自己的</b>
 * {@code budget_tokens}/{@code budget_cost}（YAML {@code agentflow:} 段）。由 BspEngine 在
 * 执行开始时按 {@code def.agentflow()} 构造，经 AgentInput 透传给记账方（mock 模式），
 * 逐节点累加 token/cost，任一项超过声明的上界即触发一次超限事件。
 *
 * <h3>Edge-triggered 语义</h3>
 * <p>{@link #record} 返回 true 仅当<b>本次调用首次把累计用量推过上界</b>；此后反复 record
 * 返回 false（不重复计 Counter）。这对"告警事件"语义是必要的——全局 {@code checkBudget}
 * 旧实现每次调用都自增（每个节点超限都 +1，事件数 = 节点数），per-workflow 应记"跨过预算"
 * 一次。
 *
 * <h3>线程安全</h3>
 * <p>{@link #record} 是 {@code synchronized}（BSP super-step 内多节点并行记账，低频率操作，
 * 锁开销可忽略），读取路径只读 volatile 标记。
 */
public final class WorkflowBudget {

    /** token 上界（null = 不检查 token 维度）。 */
    private final Long budgetTokens;
    /** 成本上界，USD（null = 不检查成本维度）。 */
    private final Double budgetCost;

    private long tokens;
    private double cost;
    private volatile boolean exceededReported;

    /**
     * @param budgetTokens token 上界（null = 禁用）
     * @param budgetCost   成本上界 USD（null = 禁用）
     */
    public WorkflowBudget(Long budgetTokens, Double budgetCost) {
        if (budgetTokens != null && budgetTokens < 0) {
            throw new IllegalArgumentException("budgetTokens 不能为负: " + budgetTokens);
        }
        if (budgetCost != null && (budgetCost < 0 || !Double.isFinite(budgetCost))) {
            throw new IllegalArgumentException("budgetCost 必须是非负有限数: " + budgetCost);
        }
        this.budgetTokens = budgetTokens;
        this.budgetCost = budgetCost;
    }

    /**
     * 累加一次 LLM 调用的用量，返回是否<b>首次</b>跨过任一预算上界。
     *
     * <p>两个维度都未配置（构造传 null）时永远返回 false（纯累加器，无告警语义）。
     *
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     * @param cost             本次成本（USD）
     * @return true = 本次首次超限（调用方应记 budget_exceeded 事件）
     */
    public synchronized boolean record(long promptTokens, long completionTokens, double cost) {
        this.tokens += promptTokens + completionTokens;
        this.cost += cost;
        boolean overTokens = budgetTokens != null && this.tokens > budgetTokens;
        boolean overCost = budgetCost != null && this.cost > budgetCost;
        if ((overTokens || overCost) && !exceededReported) {
            exceededReported = true;
            return true;
        }
        return false;
    }

    /** 是否已（曾）超限。 */
    public boolean isExceeded() {
        return exceededReported;
    }

    /** 累计 token（含本次之前）。 */
    public synchronized long tokens() {
        return tokens;
    }

    /** 累计成本（USD，含本次之前）。 */
    public synchronized double cost() {
        return cost;
    }

    /** 是否配置了至少一个预算维度。 */
    public boolean isActive() {
        return budgetTokens != null || budgetCost != null;
    }
}
