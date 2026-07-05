package com.agentflow.engine.fault;

import com.agentflow.dsl.NodeDefinition;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 超时策略（plan U4）。
 *
 * <p><b>节点级</b>：{@link NodeExecutor} 已实现——按 {@link NodeDefinition#timeout()} 解析，
 * 缺省走 {@link #nodeDefault()}（默认 120s）。本类提供节点级缺省值（供 BspEngine 注入统一配置）。
 *
 * <p><b>工作流级</b>（总超时）：{@link #workflowTotal()} 给 BspEngine 作 super-step 循环的总预算，
 * 超过则中止活跃 CompletableFuture（plan U4 测试场景 5）。可空 = 不限总超时。
 *
 * <p>不可变值对象。
 */
public final class TimeoutPolicy {

    /** 节点级缺省超时 120s（与 NodeExecutor.DEFAULT_TIMEOUT 一致）。 */
    public static final Duration DEFAULT_NODE_TIMEOUT = Duration.ofSeconds(120);

    private final Duration nodeDefault;
    private final Duration workflowTotal;

    /** 默认：节点 120s，无工作流总超时。 */
    public TimeoutPolicy() {
        this(DEFAULT_NODE_TIMEOUT, null);
    }

    public TimeoutPolicy(Duration nodeDefault, Duration workflowTotal) {
        this.nodeDefault = Objects.requireNonNull(nodeDefault, "nodeDefault");
        if (nodeDefault.isZero() || nodeDefault.isNegative()) {
            throw new IllegalArgumentException("nodeDefault 必须为正: " + nodeDefault);
        }
        if (workflowTotal != null && (workflowTotal.isZero() || workflowTotal.isNegative())) {
            throw new IllegalArgumentException("workflowTotal 必须为正或 null: " + workflowTotal);
        }
        this.workflowTotal = workflowTotal;
    }

    /** 节点级缺省超时。 */
    public Duration nodeDefault() {
        return nodeDefault;
    }

    /** 工作流总超时（可空 = 不限）。 */
    public Duration workflowTotal() {
        return workflowTotal;
    }

    /** 工作流总超时是否已耗尽（从 workflowStart 起算）。workflowTotal 为 null → 永不超时。 */
    public boolean isWorkflowExceeded(Instant workflowStart) {
        if (workflowTotal == null || workflowStart == null) {
            return false;
        }
        return Duration.between(workflowStart, Instant.now()).compareTo(workflowTotal) >= 0;
    }

    /** 工作流剩余时间（workflowTotal 为 null → 返回 null，调用方按"无超时"处理）。 */
    public Duration remainingWorkflow(Instant workflowStart) {
        if (workflowTotal == null || workflowStart == null) {
            return null;
        }
        Duration elapsed = Duration.between(workflowStart, Instant.now());
        Duration remaining = workflowTotal.minus(elapsed);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
