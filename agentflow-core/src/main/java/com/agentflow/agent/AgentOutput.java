package com.agentflow.agent;

import java.util.Map;

/**
 * Agent 执行输出（KTD-6）。
 *
 * <p>{@code channelWrites} 是该节点对 context 的<b>增量写入</b>（channel 名 → 值），
 * barrier 阶段由 {@link com.agentflow.engine.ChannelReducer} 按节点声明序合并进全局 context。
 * 并发写同一 channel 时按 Reducer 策略（OVERWRITE/CONCAT/MAX/CUSTOM）处理冲突（R6）。
 *
 * <p>便捷约定：若 channelWrites 为空且 content 非空，引擎按 channel 名 = 节点 id 写入 content，
 * 方便串行链式场景（A 的输出在 B 的 context["A"] 可见）。
 *
 * <p>U3 将新增 {@code structuredOutput: Map<String, Object>} 字段（LLM 输出 schema 校验后填充），
 * SpEL 引用优先读 structuredOutput 再降级 content。
 *
 * @param content       文本输出（v1 主路径）
 * @param channelWrites 增量 channel 写入
 */
public record AgentOutput(String content, Map<String, Object> channelWrites) {

    /** 仅文本输出（写入 channel = 节点 id 的便捷场景）。 */
    public static AgentOutput of(String content) {
        return new AgentOutput(content, Map.of());
    }

    /** 仅 channel 增量写入。 */
    public static AgentOutput of(Map<String, Object> writes) {
        return new AgentOutput(null, writes);
    }
}
