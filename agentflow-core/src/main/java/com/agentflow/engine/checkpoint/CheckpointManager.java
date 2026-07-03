package com.agentflow.engine.checkpoint;

import com.agentflow.agent.AgentOutput;
import com.agentflow.engine.WorkflowContext;

/**
 * 两级 Checkpoint 持久化 SPI（KTD-3）。
 *
 * <p>U2 引入此接口作为 BSP 循环的持久化 seam；U5 提供具体实现：
 * {@code PostgresCheckpointManager}（生产）、{@code InMemoryCheckpointManager}（开发测试）、
 * {@code RecoveryProtocol}（崩溃恢复，按 nextSuperStep 查 COMPLETED 节点）+ DB migration。
 *
 * <ul>
 *   <li>{@link #saveNodeOutput} — 节点级 checkpoint：Agent 执行完毕立即持久化 output（防 super-step 中途崩溃致 LLM 重复计费）。
 *       引擎在节点完成的当下（barrier 前）调用——R3"节点完成立即保存"字面成立。</li>
 *   <li>{@link #saveBarrier} — super-step barrier checkpoint：barrier 合并后持久化 channel 快照。</li>
 * </ul>
 *
 * <p>实现必须是线程安全的（节点级调用来自并行 Virtual Thread；U5 用 HikariCP + Semaphore(20) 限流）。
 * 工作流级状态（PENDING/RUNNING/SUCCESS/FAILED）由 U5/U14 的工作流执行表承载，不在此接口。
 */
public interface CheckpointManager {

    void saveNodeOutput(String workflowId, int superStep, String nodeId, AgentOutput output);

    void saveBarrier(String workflowId, int superStep, WorkflowContext context);
}
