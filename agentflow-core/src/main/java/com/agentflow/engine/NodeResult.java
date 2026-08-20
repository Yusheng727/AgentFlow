package com.agentflow.engine;

import com.agentflow.agent.AgentOutput;

import java.util.Map;

/**
 * 单节点执行结果（sealed 类型，异常隔离载体）。
 *
 * <p>BSP super-step 内任一节点抛异常 → 包成 {@link Failure}，<b>不影响同层其他节点</b>
 * （U2 测试场景 6）。barrier 阶段引擎收集所有 Failure，统一抛
 * {@link WorkflowExecutionException}（U4 的 ErrorHandler 将在此前插入补偿逻辑）。
 *
 * <p>U1（HITL）新增第三态 {@link ApprovalRequired}：节点需要人工审批——
 * 引擎在 barrier 处识别并暂停工作流（置 {@link WorkflowStatus#AWAITING_APPROVAL}），
 * 不 merge 输出、不 abort、不重试。
 */
public sealed interface NodeResult permits NodeResult.Success, NodeResult.Failure, NodeResult.ApprovalRequired {

    /** 节点 id。 */
    String nodeId();

    /** 成功：携带 AgentOutput。 */
    record Success(String nodeId, AgentOutput output) implements NodeResult {
    }

    /** 失败：携带异常（Agent 执行异常 / 超时 / 其他 Throwable）。 */
    record Failure(String nodeId, Throwable error) implements NodeResult {
    }

    /**
     * HITL 审批请求（U1）：节点要求人工审批，工作流在此暂停。
     *
     * @param nodeId        请求审批的节点 id
     * @param description   审批描述（给人看）
     * @param requestPayload 待审载荷（可选，给人/下游参考）
     */
    record ApprovalRequired(String nodeId, String description, Map<String, Object> requestPayload)
            implements NodeResult {
    }
}
