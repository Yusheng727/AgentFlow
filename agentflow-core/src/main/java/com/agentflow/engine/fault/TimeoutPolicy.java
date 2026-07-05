package com.agentflow.engine.fault;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 超时策略（plan U4）。
 *
 * <p><b>节点级</b>：{@link com.agentflow.engine.NodeExecutor} 已实现——按
 * {@link com.agentflow.dsl.NodeDefinition#timeout()} 解析，缺省走 {@link #nodeDefault()}（默认 120s）。
 *
 * <p><b>工作流级</b>（总超时）：{@link #workflowTotal()} 给 BspEngine 作 super-step 循环的总预算，
 * 超过则 abort（plan U4 测试场景 5）。可空 = 不限总超时。
 *
 * <p>不可变值对象（record）。
 */
public final record TimeoutPolicy(Duration nodeDefault, Duration workflowTotal) {

    /** 节点级缺省超时 120s（与 NodeExecutor.DEFAULT_TIMEOUT 一致）。 */
    public static final Duration DEFAULT_NODE_TIMEOUT = Duration.ofSeconds(120);

    /** 紧凑构造器：校验。 */
    public TimeoutPolicy {
        Objects.requireNonNull(nodeDefault, "nodeDefault");
        if (nodeDefault.isZero() || nodeDefault.isNegative()) {
            throw new IllegalArgumentException("nodeDefault 必须为正: " + nodeDefault);
        }
        if (workflowTotal != null && (workflowTotal.isZero() || workflowTotal.isNegative())) {
            throw new IllegalArgumentException("workflowTotal 必须为正或 null: " + workflowTotal);
        }
    }

    /** 默认：节点 120s，无工作流总超时。 */
    public TimeoutPolicy() {
        this(DEFAULT_NODE_TIMEOUT, null);
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
