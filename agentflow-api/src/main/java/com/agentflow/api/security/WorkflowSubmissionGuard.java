package com.agentflow.api.security;

import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.observability.CostCalculator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工作流提交守卫（真安全缺口 #2，06 OQ `POST /workflows 无 DAG/token 预算上界`）。
 *
 * <p>{@code POST /api/workflows} 提交任意 YAML，未校验即可起无限 {@code Virtual Thread} + 烧 LLM 成本。
 * 既有的 {@code budget_exceeded} 是 post-hoc（跑完才报警），本守卫在<b>提交时</b>做预防性拦截：
 * <ul>
 *   <li><b>DAG 节点数上界</b>：{@code nodeCount > maxNodes} → 拒绝（防恶意/失控提交起无界并行 VT）</li>
 *   <li><b>预估成本上界</b>：{@code estimatedCost > maxCostUsd} → 拒绝（R10 cost 预算的预防面）</li>
 * </ul>
 *
 * <h3>阈值语义</h3>
 * <p><code>maxNodes</code> / <code>maxCostUsd</code> / <code>model</code> 任一为 null 即禁用对应检查
 * （成本检查须同时有 {@code model}（查单价表）+ {@code maxCostUsd} 才生效）。
 *
 * <h3>成本估算（预防性近似，非记账）</h3>
 * <p>每个节点按 {@code prompt_template} 长度估算输入 token（{@link #CHARS_PER_TOKEN} 字符/token），
 * 输出 token 用配置的 {@code defaultOutputTokens}；基准每个节点至少计 {@code defaultInputTokens}。
 * 估算 token 经 {@link CostCalculator#cost(String, long, long)}（单价表）折算 USD 求和。
 * 仅作提交前上界 sanity check，实际消耗由 U7 metrics / mock 记账记录。
 */
public final class WorkflowSubmissionGuard {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSubmissionGuard.class);

    /** 默认最大节点数（提交守卫核心：防无界 Virtual Thread）。 */
    public static final int DEFAULT_MAX_NODES = 500;

    /** 每节点基准输入 token（防空 prompt 节点被低估）。 */
    public static final int DEFAULT_INPUT_TOKENS = 500;
    /** 每节点（单次调用）预估输出 token。 */
    public static final int DEFAULT_OUTPUT_TOKENS = 500;

    /** 中文/英文混排的粗略字符/token 比（估算用，非精确）。 */
    private static final int CHARS_PER_TOKEN = 4;

    private final CostCalculator costCalculator;
    private final String model;        // null = 成本估算/成本检查禁用
    private final Integer maxNodes;    // null = 节点数检查禁用
    private final Double maxCostUsd;   // null = 成本上限检查禁用
    private final int defaultInputTokens;
    private final int defaultOutputTokens;

    /**
     * @param costCalculator 单价表（非 null）
     * @param model          成本估算所用模型名（null 或空 → 禁用成本检查）
     * @param maxNodes       节点数上界（null → 禁用；<=0 → 立即拒绝一切非空工作流）
     * @param maxCostUsd     预估成本上界（USD，null → 禁用）
     */
    public WorkflowSubmissionGuard(CostCalculator costCalculator,
                                   String model,
                                   Integer maxNodes,
                                   Double maxCostUsd) {
        this(costCalculator, model, maxNodes, maxCostUsd,
                DEFAULT_INPUT_TOKENS, DEFAULT_OUTPUT_TOKENS);
    }

    /**
     * 完整构造（可自定义每节点 token 估算基准，测试/调参用）。
     */
    public WorkflowSubmissionGuard(CostCalculator costCalculator,
                                   String model,
                                   Integer maxNodes,
                                   Double maxCostUsd,
                                   int defaultInputTokens,
                                   int defaultOutputTokens) {
        if (costCalculator == null) {
            throw new IllegalArgumentException("costCalculator 不能为 null");
        }
        this.costCalculator = costCalculator;
        this.model = (model == null || model.isBlank()) ? null : model;
        this.maxNodes = maxNodes;
        this.maxCostUsd = maxCostUsd;
        this.defaultInputTokens = defaultInputTokens;
        this.defaultOutputTokens = defaultOutputTokens;
    }

    // ──────────────────────────── 公共 API ────────────────────────────

    /**
     * 提交前校验工作流定义。任一上界超标 → 拒绝（{@link SubmissionResult#allowed()==false}）。
     *
     * @param def 已解析的工作流定义（nodes 非空，caller 侧已保证）
     * @return 校验结果（含拒绝原因，安全可外露）
     */
    public SubmissionResult check(WorkflowDefinition def) {
        int count = def.nodes().size();
        if (maxNodes != null && count > maxNodes) {
            log.warn("提交被拒：DAG 节点数 {} 超过上限 {}", count, maxNodes);
            return SubmissionResult.reject("工作流节点数 " + count + " 超过上限 " + maxNodes);
        }

        if (model != null && maxCostUsd != null) {
            double estimated = estimateCost(def);
            if (estimated > maxCostUsd) {
                log.warn("提交被拒：预估成本 ${} 超过预算 ${}（model={}）", estimated, maxCostUsd, model);
                return SubmissionResult.reject(
                        String.format("工作流预估成本 $%.4f 超过预算 $%.2f", estimated, maxCostUsd));
            }
        }
        return SubmissionResult.ok();
    }

    /** 按当前参数（节点数 + 估算 token + 单价表）估算整个 DAG 的预防性成本（USD）。 */
    public double estimateCost(WorkflowDefinition def) {
        double total = 0;
        for (NodeDefinition node : def.nodes()) {
            long input = estimateInputTokens(node);
            total += costCalculator.cost(model, input, defaultOutputTokens);
        }
        return total;
    }

    // ──────────────────────────── 辅助 ────────────────────────────

    private long estimateInputTokens(NodeDefinition node) {
        String prompt = node.promptTemplate();
        long fromPrompt = 0;
        if (prompt != null && !prompt.isBlank()) {
            fromPrompt = prompt.length() / CHARS_PER_TOKEN;
        }
        // 基准每节点至少计 defaultInputTokens，避免空 prompt 节点把成本低估成 0
        return Math.max(defaultInputTokens, fromPrompt);
    }

    /** 提交校验结果。 */
    public record SubmissionResult(boolean allowed, String rejectionReason) {
        public static SubmissionResult ok() {
            return new SubmissionResult(true, null);
        }

        public static SubmissionResult reject(String reason) {
            return new SubmissionResult(false, reason);
        }
    }
}
