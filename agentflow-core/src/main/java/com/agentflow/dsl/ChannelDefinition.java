package com.agentflow.dsl;

/**
 * Channel 声明（R6）。上游 Agent 输出按 channel 名写入 WorkflowContext，
 * 并发写入时按 reducer 策略合并。type 为 v1.1 完整类型 schema 预留字段。
 *
 * <pre>
 * channels:
 *   financeAnalysis:
 *     reducer: overwrite
 * </pre>
 */
public record ChannelDefinition(String type, Reducer reducer) {
}
