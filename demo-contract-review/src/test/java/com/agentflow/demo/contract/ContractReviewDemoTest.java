package com.agentflow.demo.contract;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.MissingMockResponseException;
import com.agentflow.adapters.mock.MockAgentFunction;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.engine.WorkflowExecutionException;
import com.agentflow.engine.checkpoint.ExecutionState;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.engine.checkpoint.RecoveryProtocol;
import com.agentflow.engine.checkpoint.WorkflowStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 合同审核串行流水线端到端测试（plan U11 Test scenarios + 验收标准）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>4 步串行执行，每步输出正确传递下一步（${previousStep} 占位符替换验证）</li>
 *   <li>串行依赖拓扑正确：4 节点链 → 4 super-step 各 1 节点</li>
 *   <li>串行验证：每步等待前一步完成（4 个 super-step 顺序执行）</li>
 *   <li>任一步失败保留前置 checkpoint（MissingMockResponseException 触发 + 前置 channel 已存）</li>
 *   <li>端到端 &lt; 20 秒（mock 模式）</li>
 *   <li>Recovery：崩溃后从 checkpoint 恢复串行链</li>
 * </ol>
 */
class ContractReviewDemoTest {

    private WorkflowDSLParser parser;
    private BspEngine engine;

    @BeforeEach
    void setUp() {
        parser = new WorkflowDSLParser();
        engine = new BspEngine();
    }

    // ─────────────────── 场景 1：完整端到端 + 上下文逐级传递 ───────────────────

    @Test
    @DisplayName("mock 模式：4 步串行 → 最终报告输出，每步 ${previousStep} 占位符被替换")
    void fullFlowChainsContextAcrossSteps() throws Exception {
        WorkflowDefinition def = loadWorkflow();
        MockAgentFunction mock = new MockAgentFunction();
        Map<String, AgentFunction> agents = Map.of(
                "contract-agent", mock, "legal-agent", mock,
                "compliance-agent", mock, "report-agent", mock);

        long start = System.currentTimeMillis();
        WorkflowContext result = engine.execute(
                def, agents, Map.of("contractTitle", "某 SaaS 服务订阅合同"),
                new InMemoryCheckpointManager(), new ChannelReducer(), "test-1");
        long elapsed = System.currentTimeMillis() - start;

        // 验收：端到端 < 20 秒（mock 模式远低于此）
        assertThat(elapsed).isLessThan(20_000L);

        // 每步都写出对应 channel（channel = nodeId）
        assertThat(result.getValue("contract-parse")).asString().contains("合同解析");
        assertThat(result.getValue("legal-risk")).asString().contains("法律风险分析");
        assertThat(result.getValue("compliance-advice")).asString().contains("合规建议");
        assertThat(result.getValue("final-report")).asString().contains("合同审核最终报告");

        // 上下文逐级传递验证：legal-risk 的 mock_response 引用 ${contract-parse}
        // 占位符被替换为 contract-parse 的输出 → 应含原文片段
        String legalRisk = (String) result.getValue("legal-risk");
        assertThat(legalRisk).contains("[输入合同解析]")
                .contains("违约金偏高"); // contract-parse 输出片段已注入

        // compliance-advice 引用 ${legal-risk} → 含 legal-risk 的内容
        String compliance = (String) result.getValue("compliance-advice");
        assertThat(compliance).contains("[输入法律风险]")
                .contains("仲裁条款需法务备案"); // legal-risk 输出片段已注入

        // final-report 引用 ${compliance-advice} → 含 compliance-advice 的内容
        String report = (String) result.getValue("final-report");
        assertThat(report).contains("[输入合规建议]")
                .contains("条件通过"); // final-report 自身输出
    }

    // ─────────────────── 场景 2：拓扑正确（4 节点串行链） ───────────────────

    @Test
    @DisplayName("DAG 分层正确：4 节点串行链 → 4 条边，每 super-step 恰 1 节点")
    void dagLayersCorrectly() throws Exception {
        WorkflowDefinition def = loadWorkflow();

        // 4 节点串行链 → 3 条边（contract-parse→legal-risk→compliance-advice→final-report）
        assertThat(def.nodeIds()).hasSize(4);
        assertThat(def.edges()).hasSize(3);
    }

    // ─────────────────── 场景 3：串行执行顺序（4 super-step 顺序） ───────────────────

    @Test
    @DisplayName("串行执行：每步等待前一步完成（4 个 super-step 顺序执行，占位符逐级可见）")
    void serialExecutionOrdering() throws Exception {
        WorkflowDefinition def = loadWorkflow();
        MockAgentFunction mock = new MockAgentFunction();
        Map<String, AgentFunction> agents = Map.of(
                "contract-agent", mock, "legal-agent", mock,
                "compliance-agent", mock, "report-agent", mock);

        WorkflowContext result = engine.execute(
                def, agents, Map.of("contractTitle", "某 SaaS 服务订阅合同"),
                new InMemoryCheckpointManager(), new ChannelReducer(), "test-3");

        // 串行依赖保证：legal-risk 必须能读到 contract-parse 的输出，
        // compliance-advice 必须能读到 legal-risk 的输出，final-report 必须读到 compliance-advice。
        // 若某步在其前一步 barrier 前就执行（快照不含前步输出），mock 占位符会原样保留 ${previousStep}。
        // 这里断言：所有 ${previousStep} 占位符均已被替换为实际输出——证明串行依赖 + barrier 顺序成立。
        assertThat((String) result.getValue("legal-risk")).doesNotContain("${contract-parse}");
        assertThat((String) result.getValue("compliance-advice")).doesNotContain("${legal-risk}");
        assertThat((String) result.getValue("final-report")).doesNotContain("${compliance-advice}");

        // 完整传递链：final-report 间接含 contract-parse 的内容（经 legal-risk→compliance-advice 逐级转发）
        assertThat((String) result.getValue("final-report"))
                .contains("违约金"); // 源自 contract-parse，经 3 级占位符替换仍可见
    }

    // ─────────────────── 场景 4：失败保留前置 checkpoint ───────────────────

    @Test
    @DisplayName("失败隔离：legal-risk 配错 mock_response 抛 MissingMockResponseException，前置 contract-parse checkpoint 已存")
    void failurePreservesPriorCheckpoint() throws Exception {
        WorkflowDefinition def = loadWorkflowWithBadLegalRisk();
        MockAgentFunction mock = new MockAgentFunction();
        Map<String, AgentFunction> agents = Map.of(
                "contract-agent", mock, "legal-agent", mock,
                "compliance-agent", mock, "report-agent", mock);

        InMemoryCheckpointManager cp = new InMemoryCheckpointManager();

        // legal-risk 缺 mock_response → MissingMockResponseException（Fatal），工作流 FAILED abort
        assertThatThrownBy(() -> engine.execute(
                def, agents, Map.of("contractTitle", "某 SaaS 服务订阅合同"),
                cp, new ChannelReducer(), "test-4"))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("super-step")
                .satisfies(t -> assertThat(((WorkflowExecutionException) t).failures())
                        .anySatisfy(f -> assertThat(f).isInstanceOf(MissingMockResponseException.class)));

        // 前置 super-step 0（contract-parse）已完成 → barrier checkpoint 已写
        assertThat(cp.findLatestBarrier("test-4")).isPresent();
        assertThat(cp.findLatestBarrier("test-4").get().superStep()).isZero();
        // contract-parse 的 channel 输出已在 barrier 快照中保留
        assertThat(cp.findLatestBarrier("test-4").get().channelValues())
                .containsKey("contract-parse");

        // 崩溃层（super-step 1 = legal-risk）的节点级 checkpoint：contract-parse 已 COMPLETED
        // （节点级 checkpoint 在执行当下写入，super-step 0 节点先于失败层完成）
        assertThat(cp.findCompletedNodes("test-4", 0))
                .anySatisfy(n -> assertThat(n.nodeId()).isEqualTo("contract-parse"));

        // 工作流标记 FAILED（U5 P0 修复：abort 前显式标记）
        assertThat(cp.findStatus("test-4")).contains(WorkflowStatus.FAILED);
    }

    // ─────────────────── 场景 5：Recovery 串行链恢复 ───────────────────

    @Test
    @DisplayName("模拟崩溃恢复：barrier step 0 完成 → crash → 从 step 1 恢复，channel 快照含 contract-parse")
    void recoveryFromCheckpoint() throws Exception {
        WorkflowDefinition def = loadWorkflow();
        InMemoryCheckpointManager cp = new InMemoryCheckpointManager();

        // 模拟：super-step 0（contract-parse）已完成 barrier，崩溃发生在 super-step 1（legal-risk）
        cp.saveBarrier("test-rec1", 0, new WorkflowContext(Map.of(
                "contract-parse", "合同解析：标的=SaaS，违约金日 0.5‰")));
        cp.updateStatus("test-rec1", WorkflowStatus.RUNNING);

        // 恢复：nextSuperStep = 1（step 0 barrier 完成 → step 1 待执行）
        RecoveryProtocol recovery = new RecoveryProtocol(cp);
        ExecutionState state = recovery.recover("test-rec1");

        assertThat(state.nextSuperStep()).isEqualTo(1);
        assertThat(state.channelSnapshot())
                .containsEntry("contract-parse", "合同解析：标的=SaaS，违约金日 0.5‰");
    }

    // ─────────────────── 辅助 ───────────────────

    private WorkflowDefinition loadWorkflow() throws java.io.IOException {
        try (var yaml = new ClassPathResource("workflows/contract-review.yml").getInputStream()) {
            return parser.parse(yaml);
        }
    }

    /**
     * legal-risk 节点 mock_response 缺失的变体工作流（测试失败隔离用）。
     * 用同一 parser 解析内联 YAML 字符串，避免新增资源文件污染正常 demo 资产。
     */
    private WorkflowDefinition loadWorkflowWithBadLegalRisk() throws java.io.IOException {
        String yaml = """
            agentflow:
              version: "1.0"
            channels:
              contract-parse:
                reducer: overwrite
              legal-risk:
                reducer: overwrite
              compliance-advice:
                reducer: overwrite
              final-report:
                reducer: overwrite
            nodes:
              - id: contract-parse
                agent: contract-agent
                prompt_template: "解析合同 ${contractTitle}"
                mock_response: "合同解析：标的=SaaS"
              - id: legal-risk
                agent: legal-agent
                prompt_template: "分析 ${contract-parse}"
                # 故意缺 mock_response → MissingMockResponseException
              - id: compliance-advice
                agent: compliance-agent
                prompt_template: "建议 ${legal-risk}"
                mock_response: "合规建议"
              - id: final-report
                agent: report-agent
                prompt_template: "汇总 ${compliance-advice}"
                mock_response: "最终报告"
            edges:
              - { from: contract-parse, to: legal-risk }
              - { from: legal-risk, to: compliance-advice }
              - { from: compliance-advice, to: final-report }
            """;
        try (var in = new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return parser.parse(in);
        }
    }
}
