package com.agentflow.engine.checkpoint;

import com.agentflow.agent.AgentOutput;
import com.agentflow.engine.WorkflowContext;

import java.util.List;
import java.util.Optional;

/**
 * 空操作 CheckpointManager（U2 默认）。
 *
 * <p>U2 的 BSP 循环聚焦执行语义（分层/并行/barrier/Reducer/异常隔离），持久化交给 U5。
 * 在 U5 落地前用本类占位，使引擎可独立编译与单测；U5 引入 PG/InMemory 实现后由调用方注入。
 *
 * <p>所有查询方法返回空——无持久化数据可查。
 */
public final class NoopCheckpointManager implements CheckpointManager {

    @Override
    public void saveNodeOutput(String workflowId, int superStep, String nodeId, AgentOutput output) {
        // noop — U5 提供 InMemoryCheckpointManager / PostgresCheckpointManager
    }

    @Override
    public void saveBarrier(String workflowId, int superStep, WorkflowContext context) {
        // noop
    }

    @Override
    public Optional<BarrierCheckpoint> findLatestBarrier(String workflowId) {
        return Optional.empty();
    }

    @Override
    public List<NodeOutputStore> findCompletedNodes(String workflowId, int superStep) {
        return List.of();
    }

    @Override
    public void initWorkflow(String workflowId, String workflowName, String version) {
        // noop
    }

    @Override
    public void updateStatus(String workflowId, WorkflowStatus status) {
        // noop
    }
}