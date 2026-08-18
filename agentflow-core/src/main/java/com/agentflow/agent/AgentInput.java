package com.agentflow.agent;

import com.agentflow.engine.WorkflowContext;
import com.agentflow.observability.ExecutionTrace;
import com.agentflow.observability.WorkflowBudget;

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
 * <p>U9 富化：透传 {@code mockResponse}（节点声明的 mock 响应），供 {@link com.agentflow.adapters.mock.MockAgentFunction}
 * 在 mock 模式下读取预设响应，不发真实 LLM 请求。
 *
 * <p>U7 富化（KTD-2 mock 模式 trace 补齐）：透传 {@code trace}（当前 workflow 的
 * {@link ExecutionTrace}），供 {@code MockAgentFunction} 在 mock 模式下也写入 {@code NodeTrace}，
 * 使 {@code TraceController} 在 mock 模式不返回空树。真实模式（{@code SpringAiAgentAdapter}）
 * 也可选用 {@code input.trace()} 作为 per-workflow trace 来源（适配器构造器注入的 trace 字段为 fallback）。
 * 可空——不启用 trace 的调用方传 null。
 *
 * <p>R10 富化（per-workflow 预算）：透传 {@code budget}（当前 workflow 的
 * {@link WorkflowBudget}，由 BspEngine 按 {@code def.agentflow()} 构造），供记账方（mock 模式）
 * 逐节点累加 token/cost 并触发 {@code budget_exceeded}。可空——未声明预算的调用方传 null。
 *
 * @param nodeId         节点 id
 * @param agentName      节点声明的 agent 名（用于 NodeRegistry 查找）
 * @param promptTemplate 节点的 prompt 模板（含 ${...} 占位符，SpEL 解析在 U3）
 * @param context        只读快照（put 抛 UnsupportedOperationException）
 * @param inputs         工作流启动入参（POST /workflows 的 inputs，U14）
 * @param tools          节点声明的 @Tool 名列表（透传自 NodeDefinition.tools，可空）
 * @param outputSchema   节点声明的 LLM 输出 JSON Schema（透传自 NodeDefinition.outputSchema，可空）
 * @param mockResponse   节点声明的 mock 响应（透传自 NodeDefinition.mockResponse，可空——mock 模式下缺失抛 MissingMockResponseException）
 * @param trace          当前 workflow 的 ExecutionTrace（U7 引入，可空——非空时 MockAgentFunction/Adapter 写 NodeTrace）
 * @param budget         当前 workflow 的 WorkflowBudget（R10 引入，可空——非空时 mock 记账触发 budget_exceeded）
 * @param round          当前迭代轮次（v2 循环 U4 引入，默认 0——无回边工作流恒 0；节点可从 {@code input.round()} 感知「我在第几轮」）
 */
public record AgentInput(
        String nodeId,
        String agentName,
        String promptTemplate,
        WorkflowContext context,
        Map<String, Object> inputs,
        List<String> tools,
        Map<String, Object> outputSchema,
        String mockResponse,
        ExecutionTrace trace,
        WorkflowBudget budget,
        int round
) {

    /** 测试/便捷工厂：不带 tools/outputSchema/mockResponse/trace/budget/round（默认空/0）。 */
    public static AgentInput of(String nodeId, String agentName, String promptTemplate,
                                WorkflowContext context, Map<String, Object> inputs) {
        return new AgentInput(nodeId, agentName, promptTemplate, context, inputs, List.of(), Map.of(), null, null, null, 0);
    }

    /** 便捷构造：不带 budget/round（预算默认 null、轮次默认 0，向后兼容既有 9-arg 调用点）。 */
    public AgentInput(String nodeId, String agentName, String promptTemplate,
                      WorkflowContext context, Map<String, Object> inputs,
                      List<String> tools, Map<String, Object> outputSchema,
                      String mockResponse, ExecutionTrace trace) {
        this(nodeId, agentName, promptTemplate, context, inputs, tools, outputSchema, mockResponse, trace, null, 0);
    }

    /** 便捷构造：不带 round（轮次默认 0，向后兼容既有 10-arg 调用点）。 */
    public AgentInput(String nodeId, String agentName, String promptTemplate,
                      WorkflowContext context, Map<String, Object> inputs,
                      List<String> tools, Map<String, Object> outputSchema,
                      String mockResponse, ExecutionTrace trace, WorkflowBudget budget) {
        this(nodeId, agentName, promptTemplate, context, inputs, tools, outputSchema, mockResponse, trace, budget, 0);
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
