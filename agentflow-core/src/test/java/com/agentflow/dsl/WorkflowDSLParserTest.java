package com.agentflow.dsl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U1 验证：YAML 解析 + 三层校验 + 最长路径分层。
 * 覆盖 plan U1 全部 Test scenarios。
 */
class WorkflowDSLParserTest {

    private WorkflowDSLParser parser;
    private SemanticValidator validator;
    private DAGLayerer layerer;

    @BeforeEach
    void setUp() {
        parser = new WorkflowDSLParser();
        validator = new SemanticValidator();
        layerer = new DAGLayerer();
    }

    private WorkflowDefinition parseAndValidate(String yaml) {
        WorkflowDefinition def = parser.parse(yaml);
        validator.validate(def);
        return def;
    }

    // ========== 合法拓扑 ==========

    @Test
    @DisplayName("合法串行 YAML → DAG 3 节点，边 A→B→C")
    void parseSerial() {
        WorkflowDefinition def = parseAndValidate("""
                agentflow: { version: "1.0" }
                nodes:
                  - { id: A, agent: a }
                  - { id: B, agent: b }
                  - { id: C, agent: c }
                edges:
                  - { from: A, to: B }
                  - { from: B, to: C }
                """);
        assertThat(def.nodeIds()).containsExactlyInAnyOrder("A", "B", "C");
        assertThat(def.version()).isEqualTo("1.0");
        // 串行 → 3 super-step，各 1 节点
        List<List<String>> steps = layerer.computeSuperSteps(def);
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0)).containsExactly("A");
        assertThat(steps.get(1)).containsExactly("B");
        assertThat(steps.get(2)).containsExactly("C");
    }

    @Test
    @DisplayName("合法并行 YAML（fork-join）→ DAG 含分支汇聚，3 super-step")
    void parseParallelForkJoin() {
        WorkflowDefinition def = parseAndValidate("""
                nodes:
                  - { id: A, agent: a }
                  - { id: B, agent: b }
                  - { id: C, agent: c }
                  - { id: D, agent: d }
                  - { id: E, agent: e }
                edges:
                  - { from: A, to: B }
                  - { from: A, to: C }
                  - { from: A, to: D }
                  - { from: B, to: E }
                  - { from: C, to: E }
                  - { from: D, to: E }
                """);
        List<List<String>> steps = layerer.computeSuperSteps(def);
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0)).containsExactly("A");
        assertThat(steps.get(1)).containsExactlyInAnyOrder("B", "C", "D");
        assertThat(steps.get(2)).containsExactly("E");
    }

    @Test
    @DisplayName("合法混合拓扑（双层 fork-join）→ 5 super-step 正确分层")
    void parseMixedDoubleForkJoin() {
        // A→{B,C}→D→{E,F}→G，对应 plan 时序图 5 层
        WorkflowDefinition def = parseAndValidate("""
                nodes:
                  - { id: A, agent: a }
                  - { id: B, agent: b }
                  - { id: C, agent: c }
                  - { id: D, agent: d }
                  - { id: E, agent: e }
                  - { id: F, agent: f }
                  - { id: G, agent: g }
                edges:
                  - { from: A, to: B }
                  - { from: A, to: C }
                  - { from: B, to: D }
                  - { from: C, to: D }
                  - { from: D, to: E }
                  - { from: D, to: F }
                  - { from: E, to: G }
                  - { from: F, to: G }
                """);
        List<List<String>> steps = layerer.computeSuperSteps(def);
        assertThat(steps).hasSize(5);
        assertThat(steps.get(0)).containsExactly("A");
        assertThat(steps.get(1)).containsExactlyInAnyOrder("B", "C");
        assertThat(steps.get(2)).containsExactly("D");
        assertThat(steps.get(3)).containsExactlyInAnyOrder("E", "F");
        assertThat(steps.get(4)).containsExactly("G");
    }

    @Test
    @DisplayName("单节点工作流（无 edges）→ 1 super-step")
    void parseSingleNodeNoEdges() {
        WorkflowDefinition def = parseAndValidate("""
                nodes:
                  - { id: only, agent: a }
                """);
        List<List<String>> steps = layerer.computeSuperSteps(def);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsExactly("only");
    }

    // ========== 失败场景 ==========

    @Nested
    @DisplayName("校验失败")
    class ValidationFailures {

        @Test
        @DisplayName("缺少必填字段（nodes 缺失）→ WorkflowValidationException")
        void missingNodes() {
            assertThatThrownBy(() -> parseAndValidate("agentflow: { version: \"1.0\" }\n"))
                    .isInstanceOf(WorkflowValidationException.class)
                    .hasMessageContaining("nodes");
        }

        @Test
        @DisplayName("node 缺少 id → 异常带字段信息")
        void nodeMissingId() {
            assertThatThrownBy(() -> parseAndValidate("""
                    nodes:
                      - { agent: a }
                    """))
                    .isInstanceOf(WorkflowValidationException.class)
                    .hasMessageContaining("id");
        }

        @Test
        @DisplayName("引用不存在节点 → 校验失败")
        void edgeReferencesMissingNode() {
            assertThatThrownBy(() -> parseAndValidate("""
                    nodes:
                      - { id: A, agent: a }
                    edges:
                      - { from: A, to: Z }
                    """))
                    .isInstanceOf(WorkflowValidationException.class)
                    .hasMessageContaining("Z");
        }

        @Test
        @DisplayName("检测环路 → 校验失败")
        void detectsCycle() {
            assertThatThrownBy(() -> parseAndValidate("""
                    nodes:
                      - { id: A, agent: a }
                      - { id: B, agent: b }
                      - { id: C, agent: c }
                    edges:
                      - { from: A, to: B }
                      - { from: B, to: C }
                      - { from: C, to: A }
                    """))
                    .isInstanceOf(WorkflowValidationException.class)
                    .hasMessageContaining("环路");
        }

        @Test
        @DisplayName("自环边 → 校验失败")
        void detectsSelfLoop() {
            assertThatThrownBy(() -> parseAndValidate("""
                    nodes:
                      - { id: A, agent: a }
                    edges:
                      - { from: A, to: A }
                    """))
                    .isInstanceOf(WorkflowValidationException.class)
                    .hasMessageContaining("自环");
        }

        @Test
        @DisplayName("重复 node id → 校验失败")
        void duplicateNodeId() {
            assertThatThrownBy(() -> parseAndValidate("""
                    nodes:
                      - { id: A, agent: a }
                      - { id: A, agent: a }
                    """))
                    .isInstanceOf(WorkflowValidationException.class)
                    .hasMessageContaining("重复");
        }
    }

    // ========== 版本默认 ==========

    @Test
    @DisplayName("agentflow.version 缺失 → 默认 1.0")
    void versionDefaultsToOne() {
        WorkflowDefinition def = parseAndValidate("""
                nodes:
                  - { id: A, agent: a }
                """);
        assertThat(def.version()).isEqualTo("1.0");
        assertThat(def.agentflow()).isNull();
    }

    @Test
    @DisplayName("channels + reducer 正确解析（snake_case + 大小写不敏感 enum）")
    void channelsAndReducer() {
        WorkflowDefinition def = parseAndValidate("""
                channels:
                  financeAnalysis: { reducer: overwrite }
                  reputation: { reducer: concat }
                nodes:
                  - { id: A, agent: a }
                """);
        assertThat(def.channels()).hasSize(2);
        assertThat(def.channels().get("financeAnalysis").reducer()).isEqualTo(Reducer.OVERWRITE);
        assertThat(def.channels().get("reputation").reducer()).isEqualTo(Reducer.CONCAT);
    }

    @Test
    @DisplayName("node 含完整字段（prompt_template/retry/output_schema snake_case 映射）")
    void nodeFullFields() {
        WorkflowDefinition def = parseAndValidate("""
                nodes:
                  - id: financial-analysis
                    agent: finance-agent
                    prompt_template: "分析 ${supplier}"
                    timeout: 120s
                    retry: { max_attempts: 3, initial_backoff: 1s }
                    output_schema: { type: object }
                    mock_response: "风险低"
                """);
        NodeDefinition node = def.nodes().get(0);
        assertThat(node.id()).isEqualTo("financial-analysis");
        assertThat(node.promptTemplate()).isEqualTo("分析 ${supplier}");
        assertThat(node.retry().maxAttempts()).isEqualTo(3);
        assertThat(node.retry().initialBackoff()).isEqualTo("1s");
        assertThat(node.timeout()).isEqualTo("120s");
        assertThat(node.mockResponse()).isEqualTo("风险低");
        assertThat(node.outputSchema()).containsEntry("type", "object");
    }
}
