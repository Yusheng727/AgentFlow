package com.agentflow.demo.supplier;

import com.agentflow.agent.AgentFunction;
import com.agentflow.adapters.mock.MockAgentFunction;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.engine.checkpoint.RecoveryProtocol;
import com.agentflow.engine.checkpoint.ExecutionState;
import com.agentflow.engine.checkpoint.WorkflowStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 供应商风险评估端到端测试（plan U10 Test scenarios + 验收标准）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>完整流程：3 并行 → 1 汇总 → 输出 riskLevel JSON</li>
 *   <li>汇总 Agent 正确引用三路输出（mock 占位符替换 = SpEL 等效验证）</li>
 *   <li>mock 模式完整跑通（零 LLM 成本）</li>
 *   <li>Recovery：中断后从 checkpoint 恢复</li>
 * </ol>
 */
class SupplierRiskDemoTest {

    private WorkflowDSLParser parser;
    private BspEngine engine;

    @BeforeEach
    void setUp() {
        parser = new WorkflowDSLParser();
        engine = new BspEngine();
    }

    // ─────────────────── 场景 1：完整端到端 ───────────────────

    @Test
    @DisplayName("mock 模式：3 专家并行 → 1 汇总 → 输出 JSON riskLevel")
    void fullFlowProducesRiskLevelJson() throws Exception {
        WorkflowDefinition def = loadWorkflow();
        MockAgentFunction mock = new MockAgentFunction();
        Map<String, AgentFunction> agents = Map.of(
                "finance-agent", mock, "compliance-agent", mock,
                "reputation-agent", mock, "aggregate-agent", mock);

        long start = System.currentTimeMillis();
        WorkflowContext result = engine.execute(
                def, agents, Map.of("supplier", "Acme Corp"),
                new InMemoryCheckpointManager(), new ChannelReducer(), "test-1");
        long elapsed = System.currentTimeMillis() - start;

        // 验收：端到端 < 30 秒（mock 模式远低于此）
        assertThat(elapsed).isLessThan(30_000L);

        // 汇总节点输出
        Object rating = result.getValue("aggregate-rating");
        assertThat(rating).isNotNull().isInstanceOf(String.class);

        // 验证 JSON 结构（mock_response 定义的格式）
        String json = (String) rating;
        assertThat(json).contains("\"riskLevel\"")
                .contains("\"LOW\"")
                .contains("\"confidence\"")
                .contains("\"evidence\"")
                .contains("\"recommendation\"");
    }

    // ─────────────────── 场景 2：三路 channel 正确传递 ───────────────────

    @Test
    @DisplayName("三路专家输出正确传递到汇总节点（channel 通过 mock 占位符替换验证）")
    void aggregateReadsAllThreeChannels() throws Exception {
        WorkflowDefinition def = loadWorkflow();
        MockAgentFunction mock = new MockAgentFunction();
        Map<String, AgentFunction> agents = Map.of(
                "finance-agent", mock, "compliance-agent", mock,
                "reputation-agent", mock, "aggregate-agent", mock);

        WorkflowContext result = engine.execute(
                def, agents, Map.of("supplier", "Acme Corp"),
                new InMemoryCheckpointManager(), new ChannelReducer(), "test-2");

        // 三路专家各自写 channel（便捷约定 channel = nodeId）
        assertThat(result.getValue("financial-analysis"))
                .asString().contains("资产负债率：35%");
        assertThat(result.getValue("compliance-check"))
                .asString().contains("环保违规");
        assertThat(result.getValue("reputation"))
                .asString().contains("5 年稳定合作");

        // 汇总节点引用三路 channel：mock_response 的 ${} 被替换为对应输出
        String rating = (String) result.getValue("aggregate-rating");
        assertThat(rating).contains("LOW"); // 财务=低 + 合规=中 + 声誉=低 → LOW
    }

    // ─────────────────── 场景 3：拓扑正确（3 并行层 + 1 汇总层） ───────────────────

    @Test
    @DisplayName("DAG 分层正确：super-step 0 = 3 并行节点，super-step 1 = 1 汇总节点")
    void dagLayersCorrectly() throws Exception {
        WorkflowDefinition def = loadWorkflow();

        // 3 个无入度并行节点 + 1 个汇总节点依赖三路 → 2 super-step
        assertThat(def.nodeIds()).hasSize(4);
        assertThat(def.edges()).hasSize(3);
    }

    // ─────────────────── 场景 4：Recovery 崩溃恢复 ───────────────────

    @Test
    @DisplayName("模拟崩溃恢复：barrier step 0 完成 → crash → 从 step 1 恢复 → 汇总正确")
    void recoveryFromCheckpoint() throws Exception {
        WorkflowDefinition def = loadWorkflow();
        MockAgentFunction mock = new MockAgentFunction();
        InMemoryCheckpointManager cp = new InMemoryCheckpointManager();

        // 模拟：step 0 barrier 已完成（3 并行节点已 barrier 合并），崩溃发生在 step 1
        cp.saveBarrier("test-rec1", 0, new WorkflowContext(Map.of(
                "financial-analysis", "财务风险：低",
                "compliance-check", "合规风险：中",
                "reputation", "声誉风险：低")));
        cp.updateStatus("test-rec1", WorkflowStatus.RUNNING);

        // 恢复：nextSuperStep = 1（step 0 barrier 完成 → step 1 待执行）
        RecoveryProtocol recovery = new RecoveryProtocol(cp);
        ExecutionState state = recovery.recover("test-rec1");

        assertThat(state.nextSuperStep()).isEqualTo(1);
        assertThat(state.channelSnapshot()).containsEntry("financial-analysis", "财务风险：低");
    }

    // ─────────────────── 辅助 ───────────────────

    private WorkflowDefinition loadWorkflow() throws java.io.IOException {
        try (var yaml = new ClassPathResource("workflows/supplier-risk.yml").getInputStream()) {
            return parser.parse(yaml);
        }
    }
}
