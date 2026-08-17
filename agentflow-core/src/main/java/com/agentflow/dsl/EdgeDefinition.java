package com.agentflow.dsl;

/**
 * 依赖边。from 节点完成后 to 节点才可在下一 super-step 执行（R1 拓扑）。
 *
 * <pre>
 * edges:
 *   - from: financial-analysis
 *     to: aggregate-rating
 *     when: "output.riskLevel == 'high'"   # 可选：条件边谓词（v2）
 *   - from: critique
 *     to: draft                            # 回边（v2 循环）：指向更早节点、形成环
 *     when: "output.score < 0.8"           # 回边必须带 when（退出条件）
 *     loop: true
 *     max_iterations: 3                    # 回边必须带迭代上限（有界性）
 * </pre>
 */
public record EdgeDefinition(String from, String to, String when, boolean loop, Integer maxIterations) {

    /** 便捷构造：无条件边（when/loop 默认 null/false，向后兼容 v1 静态边）。 */
    public EdgeDefinition(String from, String to) {
        this(from, to, null, false, null);
    }

    /** 便捷构造：条件边（无 loop，向后兼容 v2 条件分支边）。 */
    public EdgeDefinition(String from, String to, String when) {
        this(from, to, when, false, null);
    }

    /** 边键（from->to）：路由决策持久化 / 环校验 / 去重共用单一编码（review 收敛点）。 */
    public static String edgeKey(String from, String to) {
        return from + "->" + to;
    }
}
