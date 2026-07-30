package com.agentflow.demo.supplier.agents;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;

/**
 * 行业声誉 Agent（U10 供应商风险评估 Demo）。
 */
public class ReputationAgent implements AgentFunction {

    @Override
    public AgentOutput execute(AgentInput input) {
        return AgentOutput.of("声誉风险：低\n行业口碑：良好\n合作历史：5 年稳定合作");
    }
}
