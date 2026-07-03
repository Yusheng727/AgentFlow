package com.agentflow.engine.checkpoint;

import com.agentflow.agent.AgentOutput;
import com.agentflow.engine.WorkflowContext;

/**
 * 空操作 CheckpointManager（U2 默认）。
 *
 * <p>U2 的 BSP 循环聚焦执行语义（分层/并行/barrier/Reducer/异常隔离），持久化交给 U5。
 * 在 U5 落地前用本类占位，使引擎可独立编译与单测；U5 引入 PG/InMemory 实现后由调用方注入。
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
}
