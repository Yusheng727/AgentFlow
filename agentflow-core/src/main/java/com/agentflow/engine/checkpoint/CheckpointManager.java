package com.agentflow.engine.checkpoint;

import com.agentflow.agent.AgentOutput;
import com.agentflow.engine.WorkflowContext;

import java.util.List;
import java.util.Optional;

/**
 * 两级 Checkpoint 持久化 SPI（KTD-3）。
 *
 * <p>U2 引入此接口作为 BSP 循环的持久化 seam；U5 扩展查询方法 + 工作流生命周期 +
 * 提供具体实现：{@code PostgresCheckpointManager}（生产）、{@code InMemoryCheckpointManager}（开发测试）、
 * {@code RecoveryProtocol}（崩溃恢复，按 nextSuperStep 查 COMPLETED 节点）+ DB migration。
 *
 * <h3>写入（U2 已定义，U5 实现）</h3>
 * <ul>
 *   <li>{@link #saveNodeOutput} — 节点级 checkpoint：Agent 执行完毕立即持久化 output（防 super-step 中途崩溃致 LLM 重复计费）。
 *       引擎在节点完成的当下（barrier 前）调用——R3"节点完成立即保存"字面成立。</li>
 *   <li>{@link #saveBarrier} — super-step barrier checkpoint：barrier 合并后持久化 channel 快照。
 *       仅成功 super-step 调用；失败层不写 barrier（KTD-3）。</li>
 * </ul>
 *
 * <h3>查询（U5 新增，供 RecoveryProtocol 使用）</h3>
 * <ul>
 *   <li>{@link #findLatestBarrier} — 查工作流最新 barrier checkpoint，恢复时定位到最近完成的 super-step</li>
 *   <li>{@link #findCompletedNodes} — 查指定 super-step 中状态为 COMPLETED 的节点，恢复时跳过这些节点</li>
 * </ul>
 *
 * <h3>工作流生命周期（U5 新增）</h3>
 * <ul>
 *   <li>{@link #initWorkflow} — 创建工作流执行实例记录</li>
 *   <li>{@link #updateStatus} — 更新工作流状态（PENDING → RUNNING → SUCCESS | FAILED）</li>
 * </ul>
 *
 * <p>实现必须是线程安全的（节点级调用来自并行 Virtual Thread；U5 用 HikariCP + Semaphore(20) 限流）。
 *
 * @see RecoveryProtocol 崩溃恢复逻辑（查 checkpoint 数据 + 构建 ExecutionState）
 * @see NoopCheckpointManager 空操作实现（U2 默认）
 * @see InMemoryCheckpointManager 内存实现（开发测试，重启丢失）
 * @see PostgresCheckpointManager PostgreSQL 实现（生产）
 */
public interface CheckpointManager {

    // ──────────────────────────── 写入（U2） ────────────────────────────

    /** 持久化节点级 checkpoint（引擎在节点完成当下、barrier 前调用）。 */
    void saveNodeOutput(String workflowId, int superStep, String nodeId, AgentOutput output);

    /** 持久化 barrier 级 checkpoint（引擎在 super-step barrier 成功合并后调用）。 */
    void saveBarrier(String workflowId, int superStep, WorkflowContext context);

    // ────────────────────────── 查询（U5 新增） ──────────────────────────

    /**
     * 查找工作流的最新 barrier checkpoint。
     *
     * @return 最新 barrier checkpoint，若从未 barrier 过则返回 {@code Optional.empty()}
     */
    Optional<BarrierCheckpoint> findLatestBarrier(String workflowId);

    /**
     * 查找指定 super-step 中状态为 {@link NodeStatus#COMPLETED} 的节点级 checkpoint。
     * Recovery 时用此方法获取崩溃层中已完成的节点，避免 LLM 重复调用（R3）。
     *
     * <p><b>注意：</b>仅返回 output 非空的 COMPLETED 节点（双重保护）。
     * IN_PROGRESS / FAILED 的节点不在结果中，引擎会重执行。
     *
     * @param workflowId 工作流实例 id
     * @param superStep  要查询的 super-step（崩溃层 = nextSuperStep）
     * @return COMPLETED 节点列表（可能为空）
     */
    List<NodeOutputStore> findCompletedNodes(String workflowId, int superStep);

    // ─────────────────── 工作流生命周期（U5 新增） ───────────────────

    /** 创建工作流执行实例记录（状态 = PENDING）。 */
    void initWorkflow(String workflowId, String workflowName, String version);

    /** 更新工作流执行状态。 */
    void updateStatus(String workflowId, WorkflowStatus status);
}