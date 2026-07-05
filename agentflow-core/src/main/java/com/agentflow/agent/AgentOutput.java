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
 * <p>U3 富化（plan U3 + ce-code-review seam #13）：
 * <ul>
 *   <li>{@code structuredOutput} — LLM 输出经 {@code OutputSchemaValidator} 校验后填充的结构化 Map，
 *       SpEL 引用优先读 structuredOutput 再降级 content</li>
 *   <li>{@code metadata} — 非功能 telemetry（token 统计/trace id/延迟），<b>不挤占</b> channelWrites/structuredOutput</li>
 * </ul>
 *
 * @param content          文本输出（v1 主路径）
 * @param channelWrites    增量 channel 写入
 * @param structuredOutput schema 校验后的结构化输出（可空）
 * @param metadata         telemetry 元数据（token/延迟/trace id，可空）
 */
public record AgentOutput(
        String content,
        Map<String, Object> channelWrites,
        Map<String, Object> structuredOutput,
        Map<String, Object> metadata
) {

    /** 仅文本输出（写入 channel = 节点 id 的便捷场景）。 */
    public static AgentOutput of(String content) {
        return new AgentOutput(content, Map.of(), Map.of(), Map.of());
    }

    /** 仅 channel 增量写入。 */
    public static AgentOutput of(Map<String, Object> writes) {
        return new AgentOutput(null, writes, Map.of(), Map.of());
    }

    /** 文本 + channel 写入。 */
    public static AgentOutput of(String content, Map<String, Object> writes) {
        return new AgentOutput(content, writes, Map.of(), Map.of());
    }

    /** 紧凑构造器：null 防御到不可变空 Map，避免下游 NPE。 */
    public AgentOutput {
        if (channelWrites == null) {
            channelWrites = Map.of();
        }
        if (structuredOutput == null) {
            structuredOutput = Map.of();
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }
}
