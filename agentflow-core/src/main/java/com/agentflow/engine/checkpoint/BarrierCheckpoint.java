package com.agentflow.engine.checkpoint;

import java.time.Instant;
import java.util.Map;

/**
 * Barrier 级 checkpoint（映射 {@code workflow_checkpoints} 表一行）。
 *
 * <p>U5 引入。super-step barrier 合并完成后写入，记录该 super-step 完成时刻的
 * 全局 channel 快照。Recovery 时从此快照恢复 context 状态。
 *
 * <p><b>编号约定（0-based）：</b>{@code superStep=k} 表示 super-step k 已 barrier 合并完成。
 * 崩溃发生在 super-step N 执行中 → 最新 barrier 为 N-1，下一个待执行是 N。
 *
 * @param workflowId    工作流实例 id
 * @param superStep     已完成的 super-step 编号（0-based）
 * @param channelValues 该 super-step 完成后的全局 channel 快照
 * @param completedAt   barrier 完成时间
 */
public record BarrierCheckpoint(
        String workflowId,
        int superStep,
        Map<String, Object> channelValues,
        Instant completedAt
) {
    public BarrierCheckpoint {
        if (channelValues == null) {
            channelValues = Map.of();
        }
    }
}