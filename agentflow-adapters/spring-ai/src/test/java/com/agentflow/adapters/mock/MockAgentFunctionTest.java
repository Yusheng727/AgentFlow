package com.agentflow.adapters.mock;

import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.MissingMockResponseException;
import com.agentflow.engine.WorkflowContext;

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
        return new AgentInput(nodeId, "mock-agent", null, ctx, Map.of(), List.of(), Map.of(), mockResponse);
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
        AgentInput input = new AgentInput("E", "mock-agent", null, null, Map.of(), List.of(), Map.of(), "${x}");
        AgentOutput out = agent.execute(input);
        assertThat(out.content()).isEqualTo("${x}");
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
}
