package com.agentflow.debug;

import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DryRunEngine 测试（plan U6 Test scenarios）。
 */
class DryRunTest {

    private final DryRunEngine engine = new DryRunEngine();
    private final WorkflowDSLParser parser = new WorkflowDSLParser();

    @Test
    @DisplayName("Dry-run 串行 YAML：返回完整拓扑 + 每步 input/output")
    void dryRunSerial() {
        WorkflowDefinition def = parse("""
                agentflow: { version: "1.0" }
                nodes:
                  - { id: A, agent: a, mock_response: "a-out" }
                  - { id: B, agent: b, mock_response: "${A}" }
                edges:
                  - { from: A, to: B }
                """);
        List<DryRunEngine.StepResult> results = engine.dryRun(def, Map.of());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).nodeId()).isEqualTo("A");
        assertThat(results.get(0).superStep()).isZero();
        assertThat(results.get(0).expectedOutput()).contains("a-out");

        assertThat(results.get(1).nodeId()).isEqualTo("B");
        assertThat(results.get(1).superStep()).isEqualTo(1);
        // B 的 expectedInput 应包含 channel A（来自入边）
        assertThat(results.get(1).expectedInputs()).containsKey("A");
    }

    @Test
    @DisplayName("Dry-run 无 mock_response：生成 schema 描述")
    void dryRunWithoutMockResponse() {
        WorkflowDefinition def = parse("""
                agentflow: { version: "1.0" }
                nodes:
                  - { id: A, agent: a }
                edges: []
                """);
        List<DryRunEngine.StepResult> results = engine.dryRun(def, Map.of());

        assertThat(results).hasSize(1);
        // 无 mock_response → 生成 schema 描述
        assertThat(results.get(0).expectedOutput()).contains("a(A)").contains("<generated>");
    }

    @Test
    @DisplayName("Dry-run 并行拓扑：同 super-step 多节点")
    void dryRunParallel() {
        WorkflowDefinition def = parse("""
                nodes:
                  - { id: A, agent: a, mock_response: "a" }
                  - { id: B, agent: b, mock_response: "b" }
                  - { id: C, agent: c, mock_response: "c" }
                edges: []
                """);
        List<DryRunEngine.StepResult> results = engine.dryRun(def, Map.of());

        assertThat(results).hasSize(3);
        // 3 节点同层（入度为 0）
        assertThat(results).allMatch(r -> r.superStep() == 0);
    }

    @Test
    @DisplayName("Dry-run 混合拓扑：上下游 channel 推断正确")
    void dryRunMixed() {
        WorkflowDefinition def = parse("""
                nodes:
                  - { id: A, agent: a, mock_response: "a" }
                  - { id: B, agent: b, mock_response: "b" }
                  - { id: C, agent: c, mock_response: "${A}" }
                edges:
                  - { from: A, to: C }
                  - { from: B, to: C }
                """);
        List<DryRunEngine.StepResult> results = engine.dryRun(def, Map.of());

        assertThat(results).hasSize(3);
        // C 有两条入边（A, B）
        var cResult = results.stream().filter(r -> r.nodeId().equals("C")).findFirst().orElseThrow();
        assertThat(cResult.expectedInputs()).containsKeys("A", "B");
    }

    private WorkflowDefinition parse(String yaml) {
        return parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
