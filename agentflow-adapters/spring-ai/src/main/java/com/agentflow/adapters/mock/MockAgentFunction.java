package com.agentflow.adapters.mock;

import com.agentflow.agent.AgentExecutionException;
import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.MissingMockResponseException;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.observability.AgentFlowMetrics;
import com.agentflow.observability.ExecutionTrace;
import com.agentflow.observability.NodeTrace;
import com.agentflow.observability.WorkflowBudget;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock LLM AgentFunction（U9，R13）。
 *
 * <p>不从真实 LLM 取响应，直接从 {@link AgentInput#mockResponse()}（透传自 YAML 节点的
 * {@code mock_response} 字段）返回预设内容。本地零成本调试，不发任何 LLM API 调用。
 *
 * <h3>模板替换</h3>
 * <p>支持 {@code ${channel}} 占位符替换——从 {@link AgentInput#context()} 只读快照读 channel 值，
 * 让 mock 也能验证 BSP 上下文传递（上游输出 → 下游引用）。与 U3 SpelPromptResolver 不同，
 * 本实现是轻量字符串替换（不引 SpEL），避免 core↔adapter 反向依赖。
 *
 * <h3>异常合约</h3>
 * <p>缺 {@code mock_response} → {@link MissingMockResponseException}（Fatal，配置错误不可重试）。
 *
 * <p>本类是无状态单例（per-agent-name 复用），线程安全。
 */
public final class MockAgentFunction implements AgentFunction {

    /**
     * 匹配 ${channel.name} 占位符（支持嵌套点路径，如 ${finance.riskScore}）。
     * channel 名允许连字符（如 ${contract-parse}、${financial-analysis}，与 nodeId 命名一致），
     * 故字符类含 {@code -}；点路径分隔符 {@code .} 仍用于下钻 Map。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z_][\\w.-]*)}");

    /** U7 mock token 记账：可空。非空时按 prompt/响应长度模拟 token 数，经 {@link AgentFlowMetrics#recordTokens}
     *  记 {@code tokens.consumed} + {@code cost.estimated}（供 Token 消耗/成本 Grafana 面板，mock 模式无 LLM 也能出数据）。 */
    private final AgentFlowMetrics metrics;
    /** mock 模拟的模型名（成本查表 key；可空 → CostCalculator 走 unknown/默认单价）。 */
    private final String model;
    /** mock 预算阈值（USD）：非空时每次记账后调 {@link AgentFlowMetrics#checkBudget} 触发 {@code budget_exceeded}。
     *   <b>R10 起为 fallback</b>——AgentInput 携带 per-workflow {@code budget} 时优先用 YAML 预算，
     *   else 回落本全局阈值（向后兼容）。 */
    private final Double budgetThresholdUsd;

    public MockAgentFunction() {
        this(null, null, null);
    }

    /** 便捷：带指标记账（token + cost），不查预算。 */
    public MockAgentFunction(AgentFlowMetrics metrics, String model) {
        this(metrics, model, null);
    }

    /** 完整：带指标记账 + 可选预算阈值（null = 不查预算）。 */
    public MockAgentFunction(AgentFlowMetrics metrics, String model, Double budgetThresholdUsd) {
        this.metrics = metrics;
        this.model = model;
        this.budgetThresholdUsd = budgetThresholdUsd;
    }

    @Override
    public AgentOutput execute(AgentInput input) throws AgentExecutionException {
        // U7 KTD-2：mock 模式补齐 trace——若 AgentInput 携带 ExecutionTrace（BspEngine 通过
        // ExecutionTraceRegistry 注入），构造 NodeTrace 并 addNode，succeed 时记 mock token=0。
        // 不携带（input.trace()==null）时 no-op，保持 mock 单元测试原行为不变。
        ExecutionTrace trace = input.trace();
        NodeTrace nodeTrace = trace != null ? new NodeTrace(input.nodeId(), input.agentName()) : null;
        if (nodeTrace != null) {
            trace.addNode(nodeTrace);
        }
        try {
            String mock = input.mockResponse();
            if (mock == null || mock.isBlank()) {
                throw new MissingMockResponseException(input.nodeId());
            }
            // 占位符替换：从 context 只读快照读 channel 值
            String resolved = resolvePlaceholders(mock, input.context());
            AgentOutput output = AgentOutput.of(resolved);
            // U7 mock token 记账（供 Grafana Token/成本面板）：模拟 token 消耗 + 成本，按需查预算。
            // 注：NodeTrace 仍记 token=0（真实执行路径无 LLM），此处仅影响 Micrometer 指标。
            recordMockTokens(input, resolved);
            if (nodeTrace != null) {
                // mock 模式 token=0（不发 LLM）；outputSummary 截断防止巨型 mock 内容撑爆 trace
                nodeTrace.succeed(truncate(resolved), 0L, 0L);
            }
            return output;
        } catch (AgentExecutionException ae) {
            if (nodeTrace != null) {
                nodeTrace.fail(ae.getMessage());
            }
            throw ae;
        } catch (RuntimeException re) {
            if (nodeTrace != null) {
                nodeTrace.fail(re.getMessage());
            }
            throw re;
        }
    }

    /** 截断 outputSummary 到 200 字符，避免 mock 巨型响应撑爆 trace snapshot。 */
    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    /**
     * U7 mock token/cost 记账：mock 无真实 LLM token，按 prompt/响应长度模拟<b>确定性</b> token 数
     * （~4 字符 ≈ 1 token），经 {@link AgentFlowMetrics#recordTokens} 记 {@code tokens.consumed}{agent,model}
     * 与 {@code cost.estimated}{model}，并触发预算检查。metrics 为空则 no-op。
     *
     * <p>R10 per-workflow 预算：优先用 {@link AgentInput#budget()}（BspEngine 按 YAML
     * {@code agentflow.budget_*} 构造）——{@link WorkflowBudget#record} 首次超限返回 true 时记一次
     * {@code budget_exceeded}（edge-triggered，不重复自增）。未携带 budget 时回落构造器注入的全局
     * {@code budgetThresholdUsd}（向后兼容旧全局阈值语义）。
     */
    private void recordMockTokens(AgentInput input, String resolved) {
        if (metrics == null) {
            return; // 未注入指标 → 与 U6 前行为一致（mock 不记 token/cost）
        }
        long promptChars = input.promptTemplate() == null ? 0 : input.promptTemplate().length();
        long promptTokens = Math.max(8, Math.round(promptChars / 4.0) + 8); // 基础 prompt 兜底
        long completionTokens = Math.max(1, Math.round(resolved.length() / 4.0));
        metrics.recordTokens(input.agentName(), model, promptTokens, completionTokens);
        WorkflowBudget budget = input.budget();
        if (budget != null) {
            // C1 单一真相源：与两个真实适配器共用 AgentFlowMetrics.recordBudget（成本估算 + edge-triggered 超限）
            metrics.recordBudget(budget, model, promptTokens, completionTokens);
        } else if (budgetThresholdUsd != null) {
            metrics.checkBudget(budgetThresholdUsd);
        }
    }

    /**
     * 把 ${...} 占位符替换为 context 中对应 channel 值。
     * 支持点路径（${a.b} 递归取 Map）；找不到的占位符原样保留（mock 调试时可见）。
     */
    private String resolvePlaceholders(String template, WorkflowContext context) {
        if (context == null) {
            return template;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String path = m.group(1);
            Object value = resolvePath(context, path);
            // 找到的值用 quoteReplacement 转义（值可能含 $ 或 \，避免 appendReplacement 误解析）；
            // 未找到的占位符原样保留（quoteReplacement 转义 ${} 的 $，调试时可见）
            String replacement = value != null
                    ? Matcher.quoteReplacement(String.valueOf(value))
                    : Matcher.quoteReplacement(m.group());
            m.appendReplacement(out, replacement);
        }
        m.appendTail(out);
        return out.toString();
    }

    /** 按点路径从 context 读值：先取 channel，再递归下钻 Map。 */
    private Object resolvePath(WorkflowContext context, String path) {
        // 畸形路径（末尾点 ${a.}）直接返回 null 保留占位符——String.split 会丢弃末尾空段，
        // 导致 ${a.} 被当成 ${a} 解析，若 a 持有 Map 会输出 Map.toString() 而非保留占位符。
        if (path.endsWith(".")) {
            return null;
        }
        String[] parts = path.split("\\.");
        Object current = context.getValue(parts[0]);
        for (int i = 1; i < parts.length && current != null; i++) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(parts[i]);
            } else {
                return null; // 非 Map 无法继续下钻
            }
        }
        return current;
    }
}
