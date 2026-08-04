package com.agentflow.engine.checkpoint;

import java.time.Instant;

/**
 * 工作流执行实例摘要（U10 后续 #12，看板列表端点用）。
 *
 * <p>由 {@link CheckpointManager#listByCreatedBy} 返回，供 <code>GET /api/workflows</code>
 * 列表端点聚合出仪表盘数据（id/name/status/createdAt）。
 */
public record WorkflowExecutionRecord(
        String workflowId,
        String workflowName,
        WorkflowStatus status,
        Instant createdAt
) {
}
