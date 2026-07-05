package com.agentflow.agent;

import com.agentflow.engine.WorkflowContext;

import java.util.List;
import java.util.Map;

/**
 * Agent 执行输入（KTD-6）。
 *
 * <p>{@code context} 是当前 super-step 开始时的<b>只读快照</b>（{@link WorkflowContext#readOnlySnapshot()}），
 * 同 super-step 内节点互不可见、不可变——BSP barrier 天然防竞态（KTD-1）。
 * 上一 super-step 的所有节点输出已 barrier 合并进该快照，故下游可读到上游输出。
 *
 * <p>U3 富化（ce-code-review seam #12）：从 {@link com.agentflow.dsl.NodeDefinition} 透传
 * {@code tools}（节点声明的 @Tool 名列表）与 {@code outputSchema}（LLM 输出 JSON Schema），
 * 供 {@code SpringAiAgentAdapter} 注册工具与做 schema 校验。
 *
 * @param nodeId         节点 id
 * @param agentName      节点声明的 agent 名（用于 NodeRegistry 查找）
 * @param promptTemplate 节点的 prompt 模板（含 ${...} 占位符，SpEL 解析在 U3）
 * @param context        只读快照（put 抛 UnsupportedOperationException）
 * @param inputs         工作流启动入参（POST /workflows 的 inputs，U14）
 * @param tools          节点声明的 @Tool 名列表（透传自 NodeDefinition.tools，可空）
 * @param outputSchema   节点声明的 LLM 输出 JSON Schema（透传自 NodeDefinition.outputSchema，可空）
 */
public record AgentInput(
        String nodeId,
        String agentName,
        String promptTemplate,
        WorkflowContext context,
        Map<String, Object> inputs,
        List<String> tools,
        Map<String, Object> outputSchema
) {

    /** 测试/便捷工厂：不带 tools/outputSchema（默认空）。 */
    public static AgentInput of(String nodeId, String agentName, String promptTemplate,
                                WorkflowContext context, Map<String, Object> inputs) {
        return new AgentInput(nodeId, agentName, promptTemplate, context, inputs, List.of(), Map.of());
    }

    /** 紧凑构造器：null 防御到不可变空集合，避免适配器侧 NPE。 */
    public AgentInput {
        if (tools == null) {
            tools = List.of();
        }
        if (outputSchema == null) {
            outputSchema = Map.of();
        }
        if (inputs == null) {
            inputs = Map.of();
        }
    }
}
