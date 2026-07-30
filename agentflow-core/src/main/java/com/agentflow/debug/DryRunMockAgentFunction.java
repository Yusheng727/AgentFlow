package com.agentflow.debug;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;

import java.util.stream.Collectors;

/**
 * Dry-run 内置 Mock AgentFunction（U6，core 本地，不依赖 adapter 模块）。
 *
 * <p>不调真实 LLM，不给预设 mock_response：基于节点配置生成<b>预期输入输出 schema 描述</b>。
 * 与 U9 {@code MockAgentFunction} 的区别：
 * <ul>
 *   <li>U9 MockAgentFunction 在 adapter 模块，需 {@code mock_response}，返回具体预设内容</li>
 *   <li>本类在 core 模块，<b>不要求</b> mock_response——缺失时自动生成 schema 描述（输入 channels + 输出类型）</li>
 * </ul>
 *
 * <p>这样 DryRunEngine 不需要 adapter 依赖即可独立运行。
 */
final class DryRunMockAgentFunction implements AgentFunction {

    @Override
    public AgentOutput execute(AgentInput input) {
        // 有 mock_response → 作为输出（含 ${} 占位符——dry-run 不替换，保留原样让开发者看到引用关系）
        if (input.mockResponse() != null && !input.mockResponse().isBlank()) {
            return AgentOutput.of(input.mockResponse().strip());
        }
        // 无 mock_response → 生成 schema 描述
        return AgentOutput.of(buildSchemaDescription(input));
    }

    /** 生成输入输出 schema 描述（无 mock_response 时用）。 */
    private String buildSchemaDescription(AgentInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append(input.agentName()).append("(").append(input.nodeId()).append(")");
        if (input.tools() != null && !input.tools().isEmpty()) {
            sb.append(" tools=").append(input.tools());
        }
        if (input.outputSchema() != null && !input.outputSchema().isEmpty()) {
            sb.append(" outputSchema=").append(input.outputSchema().keySet());
        }
        sb.append(" → <generated>");
        return sb.toString();
    }
}
