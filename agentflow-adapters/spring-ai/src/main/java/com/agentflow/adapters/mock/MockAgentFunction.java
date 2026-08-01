package com.agentflow.adapters.mock;

import com.agentflow.agent.AgentExecutionException;
import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.MissingMockResponseException;
import com.agentflow.engine.WorkflowContext;

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

    @Override
    public AgentOutput execute(AgentInput input) throws AgentExecutionException {
        String mock = input.mockResponse();
        if (mock == null || mock.isBlank()) {
            throw new MissingMockResponseException(input.nodeId());
        }
        // 占位符替换：从 context 只读快照读 channel 值
        String resolved = resolvePlaceholders(mock, input.context());
        return AgentOutput.of(resolved);
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
