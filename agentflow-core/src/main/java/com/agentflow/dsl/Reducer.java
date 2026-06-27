package com.agentflow.dsl;

/**
 * Channel 并发写入冲突的合并策略（R6）。
 *
 * <ul>
 *   <li>OVERWRITE — 后写覆盖先写（默认）</li>
 *   <li>CONCAT — 顺序拼接（List/字符串）</li>
 *   <li>MAX — 取较大值（数值）</li>
 *   <li>CUSTOM — 用户注册的 Reducer Bean 处理</li>
 * </ul>
 */
public enum Reducer {
    OVERWRITE,
    CONCAT,
    MAX,
    CUSTOM
}
