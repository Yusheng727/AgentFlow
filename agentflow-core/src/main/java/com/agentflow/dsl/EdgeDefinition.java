package com.agentflow.dsl;

/**
 * 依赖边。from 节点完成后 to 节点才可在下一 super-step 执行（R1 拓扑）。
 *
 * <pre>
 * edges:
 *   - from: financial-analysis
 *     to: aggregate-rating
 * </pre>
 */
public record EdgeDefinition(String from, String to) {
}
