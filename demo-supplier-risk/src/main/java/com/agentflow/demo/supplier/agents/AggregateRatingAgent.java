package com.agentflow.demo.supplier.agents;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;

/**
 * 汇总评级 Agent（U10 供应商风险评估 Demo）。
 *
 * <p>读取三路专家评估（financialAnalysis / complianceCheck / reputation），
 * 汇总为统一的 JSON 风险报告：{@code {riskLevel, confidence, evidence, recommendation}}。
 * 在 mock 模式下由 {@link com.agentflow.adapters.mock.MockAgentFunction} 接管，
 * YAML {@code mock_response} 已用 ${} 引用三路 channel 占位符替换为输出。
 */
public class AggregateRatingAgent implements AgentFunction {

    @Override
    public AgentOutput execute(AgentInput input) {
        return AgentOutput.of(
                "{\"riskLevel\":\"LOW\",\"confidence\":0.85,\"evidence\":[\"财务健康\",\"合规1次违规\",\"声誉良好\"],\"recommendation\":\"可合作\"}");
    }
}
