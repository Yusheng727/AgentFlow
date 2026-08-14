package com.agentflow.demo.conditional;

import com.agentflow.adapters.mock.MockAgentFunction;
import com.agentflow.agent.AgentFunction;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v2 条件分支端到端测试（plan U8：条件路由 + 分支汇合 + on_error 兜底）。
 */
class ConditionalRoutingDemoTest {

    private WorkflowDSLParser parser;
    private BspEngine engine;

    @BeforeEach
    void setUp() {
        parser = new WorkflowDSLParser();
        engine = new BspEngine();
    }

    @Test
    @DisplayName("条件路由 + 汇合 + on_error 兜底端到端：approve 命中、reject SKIPPED、cleanup 兜底")
    void conditionalRoutingAndOnErrorFallback() throws Exception {
        WorkflowDefinition def = loadWorkflow();
        MockAgentFunction mock = new MockAgentFunction();
        Map<String, AgentFunction> agents = Map.of(
                "classifier", mock, "approver", mock, "rejecter", mock,
                "reporter", mock, "risk-checker", mock, "cleaner", mock);

        WorkflowContext result = engine.execute(
                def, agents, Map.of(), new InMemoryCheckpointManager(), new ChannelReducer(), "demo-1");

        // 路由：classify="approved" → approve 执行、reject SKIPPED（无 channel）
        assertThat(result.getValue("approve")).isEqualTo("申请已通过");
        assertThat(result.getValue("reject")).isNull();
        // 汇合：report 执行
        assertThat(result.getValue("report")).isEqualTo("审批报告已生成");
        // on_error 兜底：risk-check 终态失败（缺 mock_response）→ cleanup 执行
        assertThat(result.getValue("cleanup")).isEqualTo("已触发兜底清理");
        assertThat(result.getValue("risk-check")).isNull();
    }

    @Test
    @DisplayName("reject 分支：classify 输出非 approved → 走默认边 reject，approve SKIPPED")
    void rejectBranchRoutesToDefault() throws Exception {
        WorkflowDefinition def = loadRejectWorkflow();
        MockAgentFunction mock = new MockAgentFunction();
        Map<String, AgentFunction> agents = Map.of(
                "classifier", mock, "approver", mock, "rejecter", mock,
                "reporter", mock, "risk-checker", mock, "cleaner", mock);

        WorkflowContext result = engine.execute(
                def, agents, Map.of(), new InMemoryCheckpointManager(), new ChannelReducer(), "demo-2");

        // classify="rejected" → 走默认边 reject，approve SKIPPED
        assertThat(result.getValue("reject")).isEqualTo("申请已拒绝");
        assertThat(result.getValue("approve")).isNull();
        assertThat(result.getValue("report")).isEqualTo("审批报告已生成");
    }

    // ─────────────────── 辅助 ───────────────────

    private WorkflowDefinition loadWorkflow() throws java.io.IOException {
        try (var yaml = new ClassPathResource("workflows/conditional-routing.yml").getInputStream()) {
            return parser.parse(yaml);
        }
    }

    /** classify 输出 "rejected" 的变体：走默认边 reject，验证二路分支的另一条路径。 */
    private WorkflowDefinition loadRejectWorkflow() throws java.io.IOException {
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: classify, agent: classifier, mock_response: "rejected" }
                  - { id: approve, agent: approver, mock_response: "申请已通过" }
                  - { id: reject, agent: rejecter, mock_response: "申请已拒绝" }
                  - { id: report, agent: reporter, mock_response: "审批报告已生成" }
                  - { id: risk-check, agent: risk-checker, on_error: cleanup }
                  - { id: cleanup, agent: cleaner, mock_response: "已触发兜底清理" }
                edges:
                  - { from: classify, to: approve, when: "output.content == 'approved'" }
                  - { from: classify, to: reject }
                  - { from: approve, to: report }
                  - { from: reject, to: report }
                  - { from: report, to: risk-check }
                """;
        try (var in = new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return parser.parse(in);
        }
    }
}
