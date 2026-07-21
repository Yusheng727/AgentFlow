package com.agentflow.engine.checkpoint;

import com.agentflow.agent.AgentOutput;
import com.agentflow.engine.WorkflowContext;

import java.util.List;
import java.util.Optional;

/**
 * 空操作 CheckpointManager（U2 默认）。
 *
 * <p>U2 的 BSP 循环聚焦执行语义，持久化交给 U5。在 U5 落地前占位。
 * 所有查询/生命周期方法返回空/默认值。
 */
public final class NoopCheckpointManager implements CheckpointManager {

    @Override
    public void saveNodeOutput(String workflowId, int superStep, String nodeId, AgentOutput output) {
        // noop
    }

    @Override
    public void saveBarrier(String workflowId, int superStep, WorkflowContext context) {
        // noop
    }

    @Override
    public Optional<?> findLatestBarrier(String workflowId) {
        return Optional.empty();
    }

    @Override
    public List<?> findCompletedNodes(String workflowId, int superStep) {
        return List.of();
    }

    @Override
    public void initWorkflow(String workflowId, String workflowName, String version, String createdBy) {
        // noop
    }

    @Override
    public void updateStatus(String workflowId, String status) {
        // noop
    }

    @Override
    public Optional<String> findCreatedBy(String workflowId) {
        return Optional.empty();
    }

    @Override
    public Optional<String> findStatus(String workflowId) {
        return Optional.empty();
    }
}