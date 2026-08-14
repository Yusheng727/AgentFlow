package com.agentflow.dsl;

/**
 * 依赖边。from 节点完成后 to 节点才可在下一 super-step 执行（R1 拓扑）。
 *
 * <pre>
 * edges:
 *   - from: financial-analysis
 *     to: aggregate-rating
 *     when: "output.riskLevel == 'high'"   # 可选：条件边谓词（v2）
 * </pre>
 */
public record EdgeDefinition(String from, String to, String when) {

    /** 便捷构造：无条件边（when 默认 null，向后兼容 v1 静态边）。 */
    public EdgeDefinition(String from, String to) {
        this(from, to, null);
    }

    /** 边键（from->to）：路由决策持久化 / 环校验 / 去重共用单一编码（review 收敛点）。 */
    public static String edgeKey(String from, String to) {
        return from + "->" + to;
    }
}
