package com.agentflow.dsl;

/**
 * YAML 顶层 agentflow 段。承载 SemVer 版本字段（R14）与 per-workflow 预算字段（R10）。
 *
 * <pre>
 * agentflow:
 *   version: "1.0"
 *   budget_tokens: 100000     # 可选：本工作流 token 消耗上界（超限记 budget_exceeded）
 *   budget_cost: 0.5          # 可选：本工作流成本上界（USD，超限记 budget_exceeded）
 * </pre>
 *
 * <p>预算字段均可空（缺失 = 不设该维度上界）。运行时由 {@link com.agentflow.observability.WorkflowBudget}
 * 逐节点累加，超限触发 {@code budget_exceeded} 指标（R10 per-workflow 告警语义，见 BspEngine/mock 记账）。
 */
public record AgentflowMeta(
        String version,
        Long budgetTokens,
        Double budgetCost
) {

    /** 便捷构造：仅版本（预算字段默认 null）。 */
    public AgentflowMeta(String version) {
        this(version, null, null);
    }
}