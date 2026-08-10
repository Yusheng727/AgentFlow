package com.agentflow.api.security;

import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.observability.CostCalculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * WorkflowSubmissionGuard 单元测试（提交守卫：DAG 节点数 / 预估成本上界）。
 *
 * <p>覆盖：节点数超限/禁用/边界、成本估算（prompt 长度 + 基准 token）、成本超预算/禁用、
 * 空节点不计零、maxNodes<=0 语义。
 */
class WorkflowSubmissionGuardTest {

    private static final CostCalculator COST = new CostCalculator();
    private static final String MODEL = "gpt-4o-mini"; // $0.15 in / $0.60 out per 1M

    // 默认基准：in 500 + out 500，每节点成本 = 500/1e6*0.15 + 500/1e6*0.60 = $0.000375
    private static final double PER_NODE_BASE_COST = 0.000375;

    private final WorkflowSubmissionGuard guard =
            new WorkflowSubmissionGuard(COST, MODEL, 10, null); // 节点数上限 10，成本检查禁用

    // ─────────────────── 节点数上界 ───────────────────

    @Test
    @DisplayName("节点数在限内 → 允许")
    void nodeCountWithinLimitAllowed() {
        assertThat(guard.check(defOf(10)).allowed()).isTrue();
    }

    @Test
    @DisplayName("节点数超限 → 拒绝，且给出可外露原因")
    void nodeCountExceedsLimitRejected() {
        var result = guard.check(defOf(11));
        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("超过上限").contains("10");
    }

    @Test
    @DisplayName("节点数恰好等于上限 → 允许（边界允许）")
    void nodeCountEqualsLimitAllowed() {
        assertThat(guard.check(defOf(10)).allowed()).isTrue();
    }

    @Test
    @DisplayName("maxNodes=null → 节点数检查禁用，任意多节点允许")
    void nullMaxNodesDisablesNodeCheck() {
        var permissive = new WorkflowSubmissionGuard(COST, MODEL, null, null);
        assertThat(permissive.check(defOf(10_000)).allowed()).isTrue();
    }

    @Test
    @DisplayName("maxNodes<=0 → 拒绝一切非空工作流（不可提交任何节点）")
    void nonPositiveMaxNodesRejectsAnyNodes() {
        var guardZero = new WorkflowSubmissionGuard(COST, MODEL, 0, null);
        assertThat(guardZero.check(defOf(1)).allowed()).isFalse();
    }

    // ─────────────────── 成本上界 ───────────────────

    @Test
    @DisplayName("预估成本超预算 → 拒绝")
    void estimatedCostOverBudgetRejected() {
        // 100 节点 × 基准 $0.000375 = $0.0375 > $0.02 预算
        var budgeted = new WorkflowSubmissionGuard(COST, MODEL, 1000, 0.02);
        var result = budgeted.check(defOf(100));
        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("预估成本").contains("预算");
    }

    @Test
    @DisplayName("预估成本在预算内 → 允许")
    void estimatedCostWithinBudgetAllowed() {
        // 10 节点 × $0.000375 = $0.00375 <= $0.02
        var budgeted = new WorkflowSubmissionGuard(COST, MODEL, 1000, 0.02);
        assertThat(budgeted.check(defOf(10)).allowed()).isTrue();
    }

    @Test
    @DisplayName("model 为空 → 成本检查禁用（忽略预算）")
    void blankModelDisablesCostCheck() {
        var noModel = new WorkflowSubmissionGuard(COST, "  ", 1000, 0.0000001); // 极小预算
        // 即使成本极高也允许（无 model 无法折算单价）
        assertThat(noModel.check(defOf(1000)).allowed()).isTrue();
    }

    @Test
    @DisplayName("maxCostUsd=null → 成本上界禁用")
    void nullBudgetDisablesCostCheck() {
        var noBudget = new WorkflowSubmissionGuard(COST, MODEL, 1000, null);
        assertThat(noBudget.check(defOf(1000)).allowed()).isTrue();
    }

    // ─────────────────── 成本估算（estimateCost / 基准） ───────────────────

    @Test
    @DisplayName("空 prompt 节点也按基准 input token 计成本（不为 0 低估）")
    void blankPromptStillCountsBaseline() {
        List<String> prompts = new java.util.ArrayList<>();
        prompts.add(null);
        prompts.add("");
        var def = defWithPrompts(prompts);
        double cost = guard.estimateCost(def);
        // 2 节点 × $0.000375（基准 in 500 + out 500）
        assertThat(cost).isEqualTo(2 * PER_NODE_BASE_COST, within(1e-9));
    }

    @Test
    @DisplayName("长 prompt 节点成本随输入长度增长（prompt 长度 折算 token / 4 字符）")
    void longPromptRaisesEstimatedCost() {
        // 基准已有 out 500 固定，这里只看输入部分随长度增长
        var shortDef = defWithPrompts(List.of("x".repeat(400)));  // 400/4=100 in，被基准 500 兜底
        var longDef = defWithPrompts(List.of("x".repeat(4000)));  // 1000 in > 基准 500
        double shortCost = guard.estimateCost(shortDef);
        double longCost = guard.estimateCost(longDef);
        assertThat(longCost).isGreaterThan(shortCost);
    }

    @Test
    @DisplayName("estimateCost = 各节点基准成本之和（多节点线性累加）")
    void estimateSumsAcrossNodes() {
        int n = 7;
        assertThat(guard.estimateCost(defOf(n)))
                .isEqualTo(n * PER_NODE_BASE_COST, within(1e-9));
    }

    // ─────────────────── 辅助 ───────────────────

    /** 构造含 N 个无 prompt 节点（id=node0..nodeN-1）的定义。 */
    private static WorkflowDefinition defOf(int n) {
        return defWithPrompts(IntStream.range(0, n).mapToObj(i -> "prompt-" + i).toList());
    }

    /** 按给定 prompt 列表构造定义（每个 prompt 一个节点）。 */
    private static WorkflowDefinition defWithPrompts(List<String> prompts) {
        List<NodeDefinition> nodes = IntStream.range(0, prompts.size())
                .mapToObj(i -> new NodeDefinition(
                        "node" + i, "agent", prompts.get(i), null, null, null, null, null))
                .toList();
        return new WorkflowDefinition(null, Map.of(), nodes, List.of());
    }
}
