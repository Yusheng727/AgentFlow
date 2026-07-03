package com.agentflow.agent;

import com.agentflow.engine.WorkflowContext;

import java.util.Map;

/**
 * Agent 执行输入（KTD-6）。
 *
 * <p>{@code context} 是当前 super-step 开始时的<b>只读快照</b>（{@link WorkflowContext#readOnlySnapshot()}），
 * 同 super-step 内节点互不可见、不可变——BSP barrier 天然防竞态（KTD-1）。
 * 上一 super-step 的所有节点输出已 barrier 合并进该快照，故下游可读到上游输出。
 *
 * @param nodeId         节点 id
 * @param agentName      节点声明的 agent 名（用于 NodeRegistry 查找）
 * @param promptTemplate 节点的 prompt 模板（含 ${...} 占位符，SpEL 解析在 U3）
 * @param context        只读快照（put 抛 UnsupportedOperationException）
 * @param inputs         工作流启动入参（POST /workflows 的 inputs，U14）
 */
public record AgentInput(
        String nodeId,
        String agentName,
        String promptTemplate,
        WorkflowContext context,
        Map<String, Object> inputs
) {
}
