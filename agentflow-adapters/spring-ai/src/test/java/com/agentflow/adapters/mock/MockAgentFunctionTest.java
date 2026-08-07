package com.agentflow.adapters.mock;

import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.MissingMockResponseException;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.observability.AgentFlowMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MockAgentFunction 单元测试（plan U9 Test scenarios）。
 *
 * <p>验证：① mock 模式用 mock_response 返回（含 ${channel} 占位符替换）② mock_response 缺失抛
 * MissingMockResponseException ③ 嵌套点路径 ${a.b} 解析。
 */
class MockAgentFunctionTest {

    private final MockAgentFunction agent = new MockAgentFunction();

    private static AgentInput inputWithMock(String nodeId, String mockResponse, WorkflowContext ctx) {
        return new AgentInput(nodeId, "mock-agent", null, ctx, Map.of(), List.of(), Map.of(), mockResponse, null);
    }

    // ─────────────────── 场景 1：mock_response 返回 + 占位符替换 ───────────────────

    @Test
    @DisplayName("mock 模式：mock_response 原样返回（无占位符）")
    void mockResponseReturnedAsIs() throws com.agentflow.agent.AgentExecutionException {
        AgentInput input = inputWithMock("A", "供应商财务风险：低", new WorkflowContext());
        AgentOutput out = agent.execute(input);
        assertThat(out.content()).isEqualTo("供应商财务风险：低");
    }

    @Test
    @DisplayName("mock 模式：${channel} 占位符替换为 context 值（验证 BSP 上下文传递）")
    void placeholderReplacedFromContext() throws com.agentflow.agent.AgentExecutionException {
        WorkflowContext ctx = new WorkflowContext(Map.of("company", "Acme Corp", "score", 88));
        AgentInput input = inputWithMock("B", "分析 ${company}，得分 ${score}", ctx);
        AgentOutput out = agent.execute(input);
        assertThat(out.content()).isEqualTo("分析 Acme Corp，得分 88");
    }

    @Test
    @DisplayName("mock 模式：嵌套点路径 ${finance.riskScore} 递归下钻 Map")
    void nestedDotPathResolves() throws com.agentflow.agent.AgentExecutionException {
        WorkflowContext ctx = new WorkflowContext(Map.of(
                "finance", Map.of("riskScore", "HIGH", "ratio", 0.75)));
        AgentInput input = inputWithMock("C", "风险=${finance.riskScore} 比率=${finance.ratio}", ctx);
        AgentOutput out = agent.execute(input);
        assertThat(out.content()).isEqualTo("风险=HIGH 比率=0.75");
    }

    @Test
    @DisplayName("mock 模式：context 中不存在的占位符原样保留（调试可见）")
    void missingPlaceholderLeftAsIs() throws com.agentflow.agent.AgentExecutionException {
        WorkflowContext ctx = new WorkflowContext(Map.of());
        AgentInput input = inputWithMock("D", "值=${nonexistent}", ctx);
        AgentOutput out = agent.execute(input);
        assertThat(out.content()).isEqualTo("值=${nonexistent}");
    }

    @Test
    @DisplayName("mock 模式：畸形末尾点 ${data.} 保留占位符（不解析成 data 通道，避免 Map.toString）")
    void trailingDotPlaceholderPreserved() throws com.agentflow.agent.AgentExecutionException {
        WorkflowContext ctx = new WorkflowContext(Map.of("data", Map.of("riskScore", "HIGH")));
        AgentInput input = inputWithMock("D2", "${data.}", ctx);
        AgentOutput out = agent.execute(input);
        // 末尾点 → 路径畸形 → 保留占位符，而非输出 Map.toString(){riskScore=HIGH}
        assertThat(out.content()).isEqualTo("${data.}");
    }

    @Test
    @DisplayName("mock 模式：context 为 null 时占位符原样保留（防御）")
    void nullContextLeavesPlaceholders() throws com.agentflow.agent.AgentExecutionException {
        AgentInput input = new AgentInput("E", "mock-agent", null, null, Map.of(), List.of(), Map.of(), "${x}", null);
        AgentOutput out = agent.execute(input);
        assertThat(out.content()).isEqualTo("${x}");
    }

    @Test
    @DisplayName("mock 模式：连字符 channel 名 ${contract-parse} 正确解析（与 nodeId 命名一致）")
    void hyphenatedChannelResolves() throws com.agentflow.agent.AgentExecutionException {
        WorkflowContext ctx = new WorkflowContext(Map.of("contract-parse", "标的=SaaS"));
        AgentInput input = inputWithMock("H", "上游=${contract-parse}", ctx);
        AgentOutput out = agent.execute(input);
        assertThat(out.content()).isEqualTo("上游=标的=SaaS");
    }

    // ─────────────────── 场景 2：mock_response 缺失 ───────────────────

    @Test
    @DisplayName("mock_response 缺失 → MissingMockResponseException（Fatal，配置错误）")
    void missingMockResponseThrows() {
        AgentInput input = inputWithMock("F", null, new WorkflowContext());
        assertThatThrownBy(() -> agent.execute(input))
                .isInstanceOf(MissingMockResponseException.class)
                .hasMessageContaining("F")
                .hasMessageContaining("mock_response");
    }

    @Test
    @DisplayName("mock_response 空白 → MissingMockResponseException")
    void blankMockResponseThrows() {
        AgentInput input = inputWithMock("G", "   ", new WorkflowContext());
        assertThatThrownBy(() -> agent.execute(input))
                .isInstanceOf(MissingMockResponseException.class);
    }

    // ─────────────────── 场景 3：mock 与真实模式切换 ───────────────────

    @Test
    @DisplayName("MockAgentFunction 无状态单例：同实例服务多个 node（不同 mockResponse）")
    void singletonServesMultipleNodes() throws com.agentflow.agent.AgentExecutionException {
        WorkflowContext ctx = new WorkflowContext(Map.of("x", "1"));
        AgentOutput out1 = agent.execute(inputWithMock("nodeA", "A=${x}", ctx));
        AgentOutput out2 = agent.execute(inputWithMock("nodeB", "B=${x}", ctx));
        assertThat(out1.content()).isEqualTo("A=1");
        assertThat(out2.content()).isEqualTo("B=1");
    }

    // ─────────────────── 场景 4：U7 trace 写入（KTD-2 mock 模式补齐） ───────────────────

    @Test
    @DisplayName("U7：AgentInput 携带 ExecutionTrace → MockAgentFunction 写 NodeTrace.succeed（token=0）")
    void writesNodeTraceWhenTracePresent() throws com.agentflow.agent.AgentExecutionException {
        com.agentflow.observability.ExecutionTrace trace = new com.agentflow.observability.ExecutionTrace("wf-u7");
        WorkflowContext ctx = new WorkflowContext();
        // 构造带 trace 的 AgentInput（模拟 BspEngine 注入）
        AgentInput input = new AgentInput("N", "mock-agent", null, ctx, Map.of(),
                List.of(), Map.of(), "mock-output", trace);

        AgentOutput out = agent.execute(input);

        assertThat(out.content()).isEqualTo("mock-output");
        assertThat(trace.nodes()).hasSize(1);
        com.agentflow.observability.NodeTrace n = trace.nodes().get(0);
        assertThat(n.nodeId()).isEqualTo("N");
        assertThat(n.agentName()).isEqualTo("mock-agent");
        assertThat(n.status()).isEqualTo(com.agentflow.observability.NodeTrace.Status.SUCCESS);
        assertThat(n.promptTokens()).isZero();
        assertThat(n.completionTokens()).isZero();
        assertThat(n.totalTokens()).isZero();
        assertThat(n.outputSummary()).isEqualTo("mock-output");
    }

    @Test
    @DisplayName("U7：AgentInput 不携带 trace → MockAgentFunction 不写 trace（向后兼容，无 NPE）")
    void noTraceNoOp() throws com.agentflow.agent.AgentExecutionException {
        // inputWithMock 构造的 AgentInput.trace() == null
        AgentInput input = inputWithMock("X", "x", new WorkflowContext());
        AgentOutput out = agent.execute(input);
        assertThat(out.content()).isEqualTo("x");
        // 不抛即通过（无 trace 不写）
    }

    @Test
    @DisplayName("U7：mock_response 缺失 + 携带 trace → MissingMockResponseException + NodeTrace.fail")
    void missingMockWithTraceFailsNode() {
        com.agentflow.observability.ExecutionTrace trace = new com.agentflow.observability.ExecutionTrace("wf-fail");
        AgentInput input = new AgentInput("F", "mock-agent", null, new WorkflowContext(), Map.of(),
                List.of(), Map.of(), null, trace);

        assertThatThrownBy(() -> agent.execute(input))
                .isInstanceOf(MissingMockResponseException.class);

        com.agentflow.observability.NodeTrace n = trace.nodes().get(0);
        assertThat(n.status()).isEqualTo(com.agentflow.observability.NodeTrace.Status.FAILED);
        assertThat(n.error()).contains("F");
    }

    @Test
    @DisplayName("U7：mock 巨型响应 → outputSummary 截断到 200 字符（防撑爆 trace snapshot）")
    void longMockOutputTruncated() throws com.agentflow.agent.AgentExecutionException {
        com.agentflow.observability.ExecutionTrace trace = new com.agentflow.observability.ExecutionTrace("wf-long");
        String huge = "x".repeat(500);
        AgentInput input = new AgentInput("L", "mock-agent", null, new WorkflowContext(), Map.of(),
                List.of(), Map.of(), huge, trace);

        agent.execute(input);

        String summary = trace.nodes().get(0).outputSummary();
        assertThat(summary).hasSizeLessThanOrEqualTo(203);  // 200 + "..."
        assertThat(summary).endsWith("...");
        // 原始 content 不截断
        // （AgentOutput.content() 保留原值，仅 trace summary 截断——此处不直接断言，因 input mock 已是 huge）
    }

    // ─────────────────── U7 mock token/成本记账（Token/成本 Grafana 面板数据源）───────────────────

    @Test
    @DisplayName("注入 AgentFlowMetrics → 按 prompt/响应长度记 tokens.consumed + cost.estimated")
    void recordsTokensAndCostWhenMetricsInjected() throws com.agentflow.agent.AgentExecutionException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry);
        MockAgentFunction m = new MockAgentFunction(metrics, "gpt-4o-mini");
        // prompt="你好"(2 字) → promptTokens = max(8, round(2/4)+8) = 9；响应 "hello world"(11) → completion = round(11/4)=3
        AgentInput input = new AgentInput("N", "mock-agent", "你好", new WorkflowContext(),
                Map.of(), List.of(), Map.of(), "hello world", null);

        m.execute(input);

        assertThat(registry.counter(AgentFlowMetrics.TOKENS_CONSUMED,
                "agent", "mock-agent", "model", "gpt-4o-mini").count()).isEqualTo(12); // 9 prompt + 3 completion
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_ESTIMATED,
                "model", "gpt-4o-mini").count()).isPositive(); // 按 gpt-4o-mini 单价算出 >0 成本
    }

    @Test
    @DisplayName("注入 metrics + 预算阈值 → 累计成本超阈值触发 budget_exceeded")
    void budgetThresholdTriggersBudgetExceeded() throws com.agentflow.agent.AgentExecutionException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry);
        // 极小阈值：单节点模拟成本（~0.00003 USD）即超限
        MockAgentFunction m = new MockAgentFunction(metrics, "gpt-4o-mini", 0.000001);
        AgentInput input = new AgentInput("B", "mock-agent", "较长的 prompt 模板内容用于产生更多 token",
                new WorkflowContext(), Map.of(), List.of(), Map.of(), "这是一段足够长的 mock 响应内容，用来确保模拟 token 与成本大于极小阈值。", null);

        m.execute(input);

        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isPositive();
    }

    @Test
    @DisplayName("不注入 metrics（默认构造）→ 不记任何 token/cost/budget 指标（向后兼容）")
    void noMetricsRecordsNothing() throws com.agentflow.agent.AgentExecutionException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry); // 仅供断言计数，未注入 mock
        MockAgentFunction m = new MockAgentFunction(); // 无 metrics

        m.execute(inputWithMock("X", "hello world 较长的 mock 响应", new WorkflowContext()));

        assertThat(registry.counter(AgentFlowMetrics.TOKENS_CONSUMED,
                "agent", "mock-agent", "model", "gpt-4o-mini").count()).isZero();
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_ESTIMATED,
                "model", "gpt-4o-mini").count()).isZero();
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isZero();
    }
}
