package com.agentflow.engine.checkpoint;

import com.agentflow.agent.AgentOutput;
import com.agentflow.engine.WorkflowContext;

import java.util.List;
import java.util.Optional;

/**
 * 两级 Checkpoint 持久化 SPI（KTD-3）。
 *
 * <p>U2 引入此接口作为 BSP 循环的持久化 seam；U5 提供查询方法 + 工作流生命周期 +
 * InMemory/Postgres 实现 + RecoveryProtocol；
 * U14 新增所有权查询 + 带 createdBy 的 initWorkflow。
 *
 * <h3>写入（U2 已定义）</h3>
 * <ul>
 *   <li>{@link #saveNodeOutput} — 节点级 checkpoint：Agent 执行完毕立即持久化 output</li>
 *   <li>{@link #saveBarrier} — super-step barrier checkpoint：barrier 合并后持久化 channel 快照</li>
 * </ul>
 *
 * <h3>查询（U5 新增，供 RecoveryProtocol 使用）</h3>
 * <ul>
 *   <li>{@link #findLatestBarrier} — 查工作流最新 barrier checkpoint</li>
 *   <li>{@link #findCompletedNodes} — 查指定 super-step 中 COMPLETED 节点</li>
 * </ul>
 *
 * <h3>工作流生命周期（U5 新增，U14 扩展）</h3>
 * <ul>
 *   <li>{@link #initWorkflow} — 创建工作流执行实例（U14 加 createdBy 参数）</li>
 *   <li>{@link #updateStatus} — 更新工作流状态</li>
 *   <li>{@link #findCreatedBy} — U14 新增：查工作流创建者（所有权校验）</li>
 * </ul>
 *
 * <p>实现必须是线程安全的。
 */
public interface CheckpointManager {

    // ──────────────────────────── 写入（U2） ────────────────────────────

    void saveNodeOutput(String workflowId, int superStep, String nodeId, AgentOutput output);

    void saveBarrier(String workflowId, int superStep, WorkflowContext context);

    // ────────────────────────── 查询（U5 新增） ──────────────────────────

    /** 查找工作流的最新 barrier checkpoint。从未 barrier 则 empty。 */
    Optional<?> findLatestBarrier(String workflowId);

    /** 查找指定 super-step 中 COMPLETED 且 output 非空的节点列表。 */
    List<?> findCompletedNodes(String workflowId, int superStep);

    // ─────────────────── 工作流生命周期（U5 新增，U14 扩展） ───────────────────

    /**
     * 创建工作流执行实例记录（状态 = PENDING）。
     *
     * @param createdBy API Key 的 SHA-256 hash（U14 新增，可空兼容 U5）
     */
    void initWorkflow(String workflowId, String workflowName, String version, String createdBy);

    /** 更新工作流执行状态。 */
    void updateStatus(String workflowId, String status);

    /**
     * U14 新增：查询工作流创建者的 SHA-256 hash。
     *
     * @return 创建者 hash，若不存在则 empty
     */
    Optional<String> findCreatedBy(String workflowId);

    /**
     * U14 新增：查询工作流执行状态。
     *
     * @return 状态名（PENDING/RUNNING/SUCCESS/FAILED），若不存在则 empty
     */
    Optional<String> findStatus(String workflowId);
}