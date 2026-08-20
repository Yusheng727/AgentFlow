package com.agentflow.engine;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.ApprovalRequiredException;

/**
 * 审批门 Agent（U1 HITL 核心占位，demo-api 复用）。
 *
 * <p>agent 名 {@code approval}（{@link ApprovalRequiredException#APPROVAL_AGENT_NAME}）：
 * 首跑 {@code input.approvalDecision()==null} → 抛 {@link ApprovalRequiredException}（请求审批）；
 * 审批恢复后 {@code approvalDecision()!=null} → 返回真实输出（审批已通过）。
 *
 * <p>实际业务门（付款/风控等）应实现自己的 AgentFunction 决定何时请求审批；
 * 本类提供零依赖的参考实现供 demo/测试。
 */
public final class ApprovalGateAgent implements AgentFunction {

    @Override
    public AgentOutput execute(AgentInput input) throws ApprovalRequiredException {
        if (input.approvalDecision() == null) {
            throw new ApprovalRequiredException(input.nodeId(), "需要人工审批: " + input.promptTemplate());
        }
        return AgentOutput.of("审批已通过（decision=" + input.approvalDecision() + "）");
    }
}