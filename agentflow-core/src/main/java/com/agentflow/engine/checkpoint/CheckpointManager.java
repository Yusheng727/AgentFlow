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
 * {@code RecoveryProtocol}（崩溃恢复，按 nextSuperStep 查 COMPLETED 节点）+ DB migration；
 * U14 扩展 {@link #initWorkflow} 加 createdBy 参数 + 新增 {@link #findCreatedBy} 所有权查询。
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
 * <h3>工作流生命周期（U5 新增，U14 扩展）</h3>
 * <ul>
 *   <li>{@link #initWorkflow} — 创建工作流执行实例记录（U14 加 createdBy 参数供所有权校验）</li>
 *   <li>{@link #updateStatus} — 更新工作流状态（PENDING → RUNNING → SUCCESS | FAILED）</li>
 *   <li>{@link #findStatus} — 查工作流状态（U5 P0 修复新增，Recovery 据 FAILED 鉴别 stray COMPLETED）</li>
 *   <li>{@link #findCreatedBy} — U14 新增：查工作流创建者（API Key SHA-256 hash，所有权校验）</li>
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

    // ─────────────────── 工作流生命周期（U5 新增，U14 扩展） ───────────────────

    /**
     * 创建工作流执行实例记录（状态 = PENDING）。
     *
     * @param workflowId   工作流实例 id
     * @param workflowName 工作流名（用于版本管理 U8）
     * @param version      工作流版本
     * @param createdBy    创建者 API Key 的 SHA-256 hash（U14 新增，可空——U5 调用方传 null 兼容）
     */
    void initWorkflow(String workflowId, String workflowName, String version, String createdBy);

    /** 更新工作流执行状态。 */
    void updateStatus(String workflowId, WorkflowStatus status);

    /**
     * 查找工作流当前执行状态（U5 P0 修复新增）。
     *
     * <p>Recovery 据此鉴别 timeout abort 后的 stray COMPLETED 记录：若工作流已被引擎标记为
     * FAILED（abort 路径显式调用 {@link #updateStatus}），Recovery 忽略崩溃层的 stray COMPLETED
     * 节点，让该层整体重跑，避免读到未经 barrier 合并的孤立 channel 输出。
     *
     * @return 工作流状态，若不存在则返回 {@code Optional.empty()}
     */
    Optional<WorkflowStatus> findStatus(String workflowId);

    /**
     * U14 新增：查询工作流创建者的 SHA-256 hash（所有权校验用）。
     *
     * <p>{@code WorkflowOwnershipChecker} 据此校验请求方的 API Key hash 与工作流创建者一致，
     * 不一致返回 403（防 IDOR）。
     *
     * @return 创建者 hash，若不存在（U5 未设 createdBy）则返回 {@code Optional.empty()}
     */
    Optional<String> findCreatedBy(String workflowId);

    /**
     * 列出某创建者在当前进程/库里的工作流执行实例（U10 后续 #12，看板列表端点用）。
     *
     * <p>createdBy 为创建者 API Key 的 SHA-256 hash；为空则返回全部（兼容 U5 早期未设 created_by 的实例）。
     * 按创建时间倒序。{@link InMemoryCheckpointManager} 与 {@link PostgresCheckpointManager} 均已实现；
     * 无实现（Noop）时默认返回空。 */
    default List<WorkflowExecutionRecord> listByCreatedBy(String createdBy) {
        return List.of();
    }

    /**
     * 查询工作流执行记录的工作流名（U8 版本管理用：恢复/冲突检测需 name 定位定义）。
     * 不存在返回 {@code Optional.empty()}。
     */
    default Optional<String> findWorkflowName(String workflowId) {
        return Optional.empty();
    }

    /**
     * 查询工作流执行记录的版本（U8 R14：恢复按 name+version 从 {@code WorkflowDefinitionStore} 取定义，
     * 冲突检测比较执行版本与最新定义版本）。
     * 不存在返回 {@code Optional.empty()}（U5 早期实例可能无版本）。
     */
    default Optional<String> findVersion(String workflowId) {
        return Optional.empty();
    }
}
