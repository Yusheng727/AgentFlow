package com.agentflow.engine;

import java.util.List;

/**
 * 一个 BSP super-step：0-based 索引 + 该层节点 id 列表。
 *
 * <p>nodeIds 按 {@link com.agentflow.dsl.DAGLayerer} 的声明序保留——barrier 合并阶段
 * 按此顺序应用 Reducer，保证并发写同一 channel 时合并结果确定（U2 测试场景 4）。
 */
public record SuperStep(int index, List<String> nodeIds) {
}
