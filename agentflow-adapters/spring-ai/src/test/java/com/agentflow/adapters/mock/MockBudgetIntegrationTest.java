package com.agentflow.adapters.mock;

import com.agentflow.agent.NodeRegistry;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.checkpoint.NoopCheckpointManager;
import com.agentflow.observability.AgentFlowMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R10 per-workflow 预算端到端（BspEngine → AgentInput.budget → MockAgentFunction 记账）。
 *
 * <p>验证完整链路：YAML {@code agentflow.budget_*} → BspEngine 构造 {@code WorkflowBudget} →
 * 经 AgentInput 穿线 → mock 逐节点累加 → 超限触发 {@code budget_exceeded} 恰好一次。
 *
 * <p>放 adapter 模块是因为同时依赖 core 引擎 + MockAgentFunction（core 不能反向依赖 adapter）。
 */
class MockBudgetIntegrationTest {

    private SimpleMeterRegistry registry;
    private AgentFlowMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AgentFlowMetrics(registry);
    }

    private static WorkflowDefinition parse(String yaml) throws Exception {
        return new WorkflowDSLParser().parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private NodeRegistry mockAgents() {
        MockAgentFunction mock = new MockAgentFunction(metrics, "gpt-4o-mini");
        return new NodeRegistry(name -> mock);
    }

    @Test
    @DisplayName("budget_cost 超限 → budget_exceeded 记 1 次（edge-triggered，engine 构造并穿线）")
    void budgetCostExceededFiresOnce() throws Exception {
        BspEngine engine = new BspEngine(new DAGLayerer(), null, null, null, null, metrics);
        String yaml = """
                agentflow:
                  version: "1.0"
                  budget_cost: 0.0000001
                nodes:
                  - { id: A, agent: mock, mock_response: "a" }
                  - { id: B, agent: mock, mock_response: "${A}-b" }
                edges:
                  - { from: A, to: B }
                """;

        engine.execute(parse(yaml), mockAgents(), Map.of(),
                new NoopCheckpointManager(), new ChannelReducer(), "wf-budget-cost");

        // 2 节点都超预算界限，但 edge-triggered：只记 1 次
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("budget_tokens 超限 → budget_exceeded 记 1 次")
    void budgetTokensExceededFiresOnce() throws Exception {
        BspEngine engine = new BspEngine(new DAGLayerer(), null, null, null, null, metrics);
        // mock 估算：prompt_template 未设 → 基准 8 prompt + 响应 "a" → 1 completion = 9 token/节点 > 5
        String yaml = """
                agentflow:
                  version: "1.0"
                  budget_tokens: 5
                nodes:
                  - { id: A, agent: mock, mock_response: "a" }
                """;

        engine.execute(parse(yaml), mockAgents(), Map.of(),
                new NoopCheckpointManager(), new ChannelReducer(), "wf-budget-tokens");

        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("budget_cost 未超限 → budget_exceeded 不记")
    void budgetWithinLimitNoEvent() throws Exception {
        BspEngine engine = new BspEngine(new DAGLayerer(), null, null, null, null, metrics);
        String yaml = """
                agentflow:
                  version: "1.0"
                  budget_cost: 100.0
                nodes:
                  - { id: A, agent: mock, mock_response: "a" }
                """;

        engine.execute(parse(yaml), mockAgents(), Map.of(),
                new NoopCheckpointManager(), new ChannelReducer(), "wf-budget-ok");

        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isZero();
    }

    @Test
    @DisplayName("YAML 未声明预算 → budget_exceeded 不记（向后兼容）")
    void noBudgetNoEvent() throws Exception {
        BspEngine engine = new BspEngine(new DAGLayerer(), null, null, null, null, metrics);
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: A, agent: mock, mock_response: "a" }
                """;

        engine.execute(parse(yaml), mockAgents(), Map.of(),
                new NoopCheckpointManager(), new ChannelReducer(), "wf-budget-none");

        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isZero();
    }
}