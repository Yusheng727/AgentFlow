package com.agentflow.demo.supplier.agents;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;

/**
 * 财务分析 Agent（U10 供应商风险评估 Demo）。
 *
 * <p>分析供应商的财务健康度：资产负债率、现金流、盈利能力。
 * mock 模式下使用 YAML {@code mock_response} 预设响应；真实模式下委托 Spring AI ChatClient。
 */
public class FinancialAnalysisAgent implements AgentFunction {

    @Override
    public AgentOutput execute(AgentInput input) {
        // mock 模式下 MockAgentFunction 接管，此处为真实模式备选
        return AgentOutput.of("财务风险：低\n资产负债率：35%\n现金流：稳定");
    }
}
