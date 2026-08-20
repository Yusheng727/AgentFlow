package com.agentflow.agent;

import com.agentflow.engine.WorkflowContext;
import com.agentflow.engine.checkpoint.ApprovalDecision;
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
 * <p>U3 富化（ce-code-review seam #12）：透传 {@code tools}（节点声明的 @Tool 名列表）与
 * {@code outputSchema}（LLM 输出 JSON Schema），供适配器注册工具与做 schema 校验。
 *
 * <p>U9 富化：透传 {@code mockResponse}（节点声明的 mock 响应）。
 *
 * <p>U7 富化（KTD-2 mock 模式 trace 补齐）：透传 {@code trace}（当前 workflow 的 ExecutionTrace），
 * 供 mock 模式写入 NodeTrace。
 *
 * <p>R10 富化（per-workflow 预算）：透传 {@code budget}（当前 workflow 的 WorkflowBudget）。
 *
 * <p>v2 循环：{@code round} 当前迭代轮次（默认 0）。
 *
 * <p>U3HITL（审批）：{@code approvalDecision} 非空表示节点处于「审批恢复」执行（approve/reject 后由
 * 引擎注入）；首跑为空。审批门 agent 据此分叉（空 → 抛 {@link ApprovalRequiredException}）。
 *
 * @param nodeId         节点 id
 * @param agentName      节点声明的 agent 名
 * @param promptTemplate 节点的 prompt 模板（含 ${...} 占位符）
 * @param context        只读快照（put 抛 UnsupportedOperationException）
 * @param inputs         工作流启动入参
 * @param tools          节点声明的 @Tool 名列表（可空）
 * @param outputSchema   节点声明的 LLM 输出 JSON Schema（可空）
 * @param mockResponse   节点声明的 mock 响应（可空）
 * @param trace          当前 workflow 的 ExecutionTrace（可空）
 * @param budget         当前 workflow 的 WorkflowBudget（可空）
 * @param round          当前迭代轮次（默认 0）
 * @param approvalDecision 审批恢复决策（U1 HITL，可空；非空 = 恢复注入）
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
        int round,
        ApprovalDecision approvalDecision
) {

    /** 测试/便捷工厂：不带 tools/outputSchema/mockResponse/trace/budget/round/approvalDecision（默认空/0/null）。 */
    public static AgentInput of(String nodeId, String agentName, String promptTemplate,
                                WorkflowContext context, Map<String, Object> inputs) {
        return new AgentInput(nodeId, agentName, promptTemplate, context, inputs, List.of(), Map.of(),
                null, null, null, 0, null);
    }

    /** 便捷构造：不带 budget/round/approvalDecision（默认 null/0/null，向后兼容既有 9-arg 调用点）。 */
    public AgentInput(String nodeId, String agentName, String promptTemplate,
                      WorkflowContext context, Map<String, Object> inputs,
                      List<String> tools, Map<String, Object> outputSchema,
                      String mockResponse, ExecutionTrace trace) {
        this(nodeId, agentName, promptTemplate, context, inputs, tools, outputSchema, mockResponse,
                trace, null, 0, null);
    }

    /** 便捷构造：不带 round/approvalDecision（默认 0/null，向后兼容既有 10-arg 调用点）。 */
    public AgentInput(String nodeId, String agentName, String promptTemplate,
                      WorkflowContext context, Map<String, Object> inputs,
                      List<String> tools, Map<String, Object> outputSchema,
                      String mockResponse, ExecutionTrace trace, WorkflowBudget budget) {
        this(nodeId, agentName, promptTemplate, context, inputs, tools, outputSchema, mockResponse,
                trace, budget, 0, null);
    }

    /** 便捷构造：不带 approvalDecision（默认 null，向后兼容既有 11-arg 调用点）。 */
    public AgentInput(String nodeId, String agentName, String promptTemplate,
                      WorkflowContext context, Map<String, Object> inputs,
                      List<String> tools, Map<String, Object> outputSchema,
                      String mockResponse, ExecutionTrace trace, WorkflowBudget budget, int round) {
        this(nodeId, agentName, promptTemplate, context, inputs, tools, outputSchema, mockResponse,
                trace, budget, round, null);
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