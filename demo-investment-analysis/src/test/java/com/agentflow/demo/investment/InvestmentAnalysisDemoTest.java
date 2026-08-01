package com.agentflow.demo.investment;

import com.agentflow.agent.AgentFunction;
import com.agentflow.adapters.mock.MockAgentFunction;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.observability.ExecutionTrace;
import com.agentflow.observability.NodeTrace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 投资分析决策端到端测试（plan U12 Test scenarios + 验收标准）。
 *
 * <p>覆盖双层 fork-join 混合拓扑（6 节点 4 super-step）：
 * <ol>
 *   <li>6 节点、4 super-step 正确分层（0-based：step 0/1/2/3）</li>
 *   <li>上层并行结果正确传递下层串行（${...} 占位符替换验证）</li>
 *   <li>最终汇总引用前 3 层全部输出</li>
 *   <li>ExecutionTrace 展示完整 4 层结构</li>
 *   <li>channel 隔离：同 super-step 并行节点 channel 不互相污染</li>
 *   <li>端到端 &lt; 40s（mock 模式）</li>
 * </ol>
 *
 * <p><b>命名约定</b>：node id / channel 用下划线（非连字符）—— MockAgentFunction 的占位符正则
 * {@code [a-zA-Z_][\w.]*} 不支持连字符，下划线可被 {@code ${channel}} 正确解析替换，
 * 从而能验证上游输出跨 super-step 传递到下游 mock_response。
 *
 * <p><b>ExecutionTrace 说明</b>：mock 模式不经 SpringAiAgentAdapter，引擎不自动写 ExecutionTrace
 * （NodeTrace 由适配器拥有，OQ-3 决议）。本测试场景 4 手工构造 ExecutionTrace 反映 4 层执行结构，
 * 验证 trace 基础设施能承载完整分层执行视图（U6 DiagnosisService / U7 TraceController 读同一结构）。
 */
class InvestmentAnalysisDemoTest {

    private WorkflowDSLParser parser;
    private BspEngine engine;

    @BeforeEach
    void setUp() {
        parser = new WorkflowDSLParser();
        engine = new BspEngine();
    }

    // ─────────────────── 场景 1：6 节点 4 super-step 正确分层 ───────────────────

    @Test
    @DisplayName("DAG 分层正确：4 super-step = [2 并行, 1 串行, 2 并行, 1 汇总]")
    void dagLayersFourSuperSteps() throws Exception {
        WorkflowDefinition def = loadWorkflow();

        // 拓扑：6 节点 6 边 → 最长路径分层 4 super-step
        assertThat(def.nodeIds()).hasSize(6);
        assertThat(def.edges()).hasSize(6);

        List<List<String>> steps = new DAGLayerer().computeSuperSteps(def);
        assertThat(steps).hasSize(4);

        // 0-based：step 0 = 2 并行采集，step 1 = 1 可行性，step 2 = 2 并行评估，step 3 = 1 裁决
        assertThat(steps.get(0))
                .containsExactlyInAnyOrder("company_finance", "market_data");
        assertThat(steps.get(1)).containsExactly("feasibility_analysis");
        assertThat(steps.get(2))
                .containsExactlyInAnyOrder("risk_assessment", "return_forecast");
        assertThat(steps.get(3)).containsExactly("investment_decision");
    }

    // ─────────────────── 场景 2：上层并行 → 下层串行 占位符传递 ───────────────────

    @Test
    @DisplayName("step0 并行输出正确传递到 step1 串行（${company_finance}/${market_data} 占位符替换验证）")
    void parallelOutputsPropagateToSerialStep() throws Exception {
        WorkflowDefinition def = loadWorkflow();
        MockAgentFunction mock = new MockAgentFunction();
        Map<String, AgentFunction> agents = agentsMap(mock);

        WorkflowContext result = engine.execute(
                def, agents, Map.of("target", "Acme Tech"),
                new InMemoryCheckpointManager(), new ChannelReducer(), "test-2");

        // step0 两路 channel 各自落库（channel = nodeId 便捷约定）
        assertThat(result.getValue("company_finance"))
                .asString().contains("营收：120 亿");
        assertThat(result.getValue("market_data"))
                .asString().contains("PE：22 倍");

        // step1 feasibility_analysis 的 mock_response 含占位符 ${company_finance}/${market_data}，
        // 被替换为上游真实输出 → 证明 step0 并行结果已传到 step1 串行节点
        String feasibility = (String) result.getValue("feasibility_analysis");
        assertThat(feasibility).contains("营收：120 亿")   // 来自 company_finance
                .contains("PE：22 倍");                    // 来自 market_data
        // 占位符未被原样保留（替换确实发生）
        assertThat(feasibility).doesNotContain("${company_finance}")
                .doesNotContain("${market_data}");
    }

    // ─────────────────── 场景 3：最终汇总引用前 3 层全部输出 ───────────────────

    @Test
    @DisplayName("investment_decision 汇总引用前 3 层全部输出（5 个 ${...} 占位符全部替换）")
    void finalDecisionAggregatesAllUpstreamLayers() throws Exception {
        WorkflowDefinition def = loadWorkflow();
        MockAgentFunction mock = new MockAgentFunction();
        Map<String, AgentFunction> agents = agentsMap(mock);

        WorkflowContext result = engine.execute(
                def, agents, Map.of("target", "Acme Tech"),
                new InMemoryCheckpointManager(), new ChannelReducer(), "test-3");

        String decision = (String) result.getValue("investment_decision");

        // 验收：输出 JSON 含 riskLevel / confidence / evidence / recommendation
        assertThat(decision).contains("\"riskLevel\"")
                .contains("\"MEDIUM\"")
                .contains("\"confidence\"")
                .contains("\"evidence\"")
                .contains("\"recommendation\"");

        // 汇总节点 mock_response 的 upstream 段含 5 个占位符，全部应被前 3 层输出替换
        // step0：company_finance / market_data
        assertThat(decision).contains("营收：120 亿")
                .contains("PE：22 倍");
        // step1：feasibility_analysis
        assertThat(decision).contains("基本面：优秀")
                .contains("安全边际充足");
        // step2：risk_assessment / return_forecast
        assertThat(decision).contains("综合风险等级：MEDIUM")
                .contains("年化预期收益率：18%");

        // 无残留占位符 → 前 3 层全部输出确实传到汇总节点
        assertThat(decision).doesNotContain("${company_finance}")
                .doesNotContain("${market_data}")
                .doesNotContain("${feasibility_analysis}")
                .doesNotContain("${risk_assessment}")
                .doesNotContain("${return_forecast}");
    }

    // ─────────────────── 场景 4：ExecutionTrace 展示完整 4 层结构 ───────────────────

    @Test
    @DisplayName("ExecutionTrace 承载完整 4 层执行结构（6 节点全部 terminal）")
    void executionTraceCapturesFourLayers() throws Exception {
        WorkflowDefinition def = loadWorkflow();
        List<List<String>> steps = new DAGLayerer().computeSuperSteps(def);
        assertThat(steps).hasSize(4);

        // mock 模式不经适配器，引擎不自动写 trace（OQ-3：NodeTrace 由 SpringAiAgentAdapter 拥有）。
        // 此处手工构造 ExecutionTrace 反映 4 层执行结构，验证 trace 基础设施能承载完整分层视图
        // （U6 DiagnosisService / U7 TraceController 读同一 ExecutionTrace.snapshot()）。
        ExecutionTrace trace = new ExecutionTrace("demo-investment-analysis-trace");
        for (List<String> layer : steps) {
            for (String nodeId : layer) {
                NodeTrace nt = new NodeTrace(nodeId, agentNameFor(nodeId));
                nt.succeed("mock-" + nodeId, 0, 0);
                trace.addNode(nt);
            }
        }
        trace.markCompleted(ExecutionTrace.Status.COMPLETED);

        // 6 节点全部 terminal，trace 完整覆盖 4 层结构
        ExecutionTrace.Snapshot snapshot = trace.snapshot();
        assertThat(snapshot.nodes()).hasSize(6);
        assertThat(trace.terminalNodeCount()).isEqualTo(6);
        assertThat(snapshot.status()).isEqualTo(ExecutionTrace.Status.COMPLETED);
        assertThat(snapshot.nodes()).map(NodeTrace::nodeId)
                .containsExactlyInAnyOrder(
                        "company_finance", "market_data", "feasibility_analysis",
                        "risk_assessment", "return_forecast", "investment_decision");
        // 每个节点 trace 状态成功
        assertThat(snapshot.nodes()).allSatisfy(nt ->
                assertThat(nt.status()).isEqualTo(NodeTrace.Status.SUCCESS));
    }

    // ─────────────────── 场景 5：channel 隔离 ───────────────────

    @Test
    @DisplayName("channel 隔离：step0 同 super-step 两并行节点 channel 互不污染")
    void parallelChannelsIsolated() throws Exception {
        WorkflowDefinition def = loadWorkflow();
        MockAgentFunction mock = new MockAgentFunction();
        Map<String, AgentFunction> agents = agentsMap(mock);

        WorkflowContext result = engine.execute(
                def, agents, Map.of("target", "Acme Tech"),
                new InMemoryCheckpointManager(), new ChannelReducer(), "test-5");

        // BSP 语义：同 super-step 节点读只读快照互不可见，barrier 后按 Reducer 合并。
        // company_finance / market_data 各写独立 channel（channel=nodeId），不串：
        Object finance = result.getValue("company_finance");
        Object market = result.getValue("market_data");

        assertThat(finance).isInstanceOf(String.class);
        assertThat(market).isInstanceOf(String.class);
        // company_finance channel 只含财报内容，不含市场数据
        assertThat((String) finance).contains("营收：120 亿")
                .doesNotContain("PE：22 倍");
        // market_data channel 只含市场数据，不含财报内容
        assertThat((String) market).contains("PE：22 倍")
                .doesNotContain("营收：120 亿");

        // step2 两并行同理：risk_assessment / return_forecast channel 互不污染
        String risk = (String) result.getValue("risk_assessment");
        String ret = (String) result.getValue("return_forecast");
        assertThat(risk).contains("综合风险等级：MEDIUM")
                .doesNotContain("年化预期收益率");
        assertThat(ret).contains("年化预期收益率：18%")
                .doesNotContain("综合风险等级");
    }

    // ─────────────────── 场景 6：端到端 < 40s ───────────────────

    @Test
    @DisplayName("mock 模式端到端 < 40 秒（复杂双层拓扑）")
    void endToEndUnderFortySeconds() throws Exception {
        WorkflowDefinition def = loadWorkflow();
        MockAgentFunction mock = new MockAgentFunction();
        Map<String, AgentFunction> agents = agentsMap(mock);

        long start = System.currentTimeMillis();
        WorkflowContext result = engine.execute(
                def, agents, Map.of("target", "Acme Tech"),
                new InMemoryCheckpointManager(), new ChannelReducer(), "test-6");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(40_000L);
        assertThat(result.getValue("investment_decision")).isNotNull();
    }

    // ─────────────────── 辅助 ───────────────────

    private static Map<String, AgentFunction> agentsMap(MockAgentFunction mock) {
        return Map.of(
                "finance-agent", mock,
                "market-agent", mock,
                "feasibility-agent", mock,
                "risk-agent", mock,
                "return-agent", mock,
                "decision-agent", mock);
    }

    private static String agentNameFor(String nodeId) {
        return switch (nodeId) {
            case "company_finance" -> "finance-agent";
            case "market_data" -> "market-agent";
            case "feasibility_analysis" -> "feasibility-agent";
            case "risk_assessment" -> "risk-agent";
            case "return_forecast" -> "return-agent";
            case "investment_decision" -> "decision-agent";
            default -> "unknown-agent";
        };
    }

    private WorkflowDefinition loadWorkflow() throws java.io.IOException {
        try (var yaml = new ClassPathResource("workflows/investment-analysis.yml").getInputStream()) {
            return parser.parse(yaml);
        }
    }
}
