package com.agentflow.engine;

import com.agentflow.agent.AgentOutput;

/**
 * 单节点执行结果（sealed 类型，异常隔离载体）。
 *
 * <p>BSP super-step 内任一节点抛异常 → 包成 {@link Failure}，<b>不影响同层其他节点</b>
 * （U2 测试场景 6）。barrier 阶段引擎收集所有 Failure，统一抛
 * {@link WorkflowExecutionException}（U4 的 ErrorHandler 将在此前插入补偿逻辑）。
 */
public sealed interface NodeResult permits NodeResult.Success, NodeResult.Failure {

    /** 节点 id。 */
    String nodeId();

    /** 成功：携带 AgentOutput。 */
    record Success(String nodeId, AgentOutput output) implements NodeResult {
    }

    /** 失败：携带异常（Agent 执行异常 / 超时 / 其他 Throwable）。 */
    record Failure(String nodeId, Throwable error) implements NodeResult {
    }
}
