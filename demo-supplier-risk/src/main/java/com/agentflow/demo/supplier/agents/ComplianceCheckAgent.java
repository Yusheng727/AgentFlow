package com.agentflow.demo.supplier.agents;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;

/**
 * 合规检查 Agent（U10 供应商风险评估 Demo）。
 */
public class ComplianceCheckAgent implements AgentFunction {

    @Override
    public AgentOutput execute(AgentInput input) {
        return AgentOutput.of("合规风险：中\n营业执照：有效\n近 2 年有 1 次环保违规记录");
    }
}
