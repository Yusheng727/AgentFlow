package com.agentflow.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U3 验证：回边豁免分层——静态图（去回边）仍可最长路径分层，回边目标靠非回边入边获得层号。
 */
class DAGLayererTest {

    private final WorkflowDSLParser parser = new WorkflowDSLParser();
    private final DAGLayerer layerer = new DAGLayerer();

    @Test
    @DisplayName("反思循环（回边豁免）→ draft 层 0、critique 层 1、finalize 层 2")
    void backedgeExemptedFromLayering() {
        WorkflowDefinition def = parser.parse("""
                nodes:
                  - { id: draft, agent: writer }
                  - { id: critique, agent: reviewer }
                  - { id: finalize, agent: editor }
                edges:
                  - { from: draft, to: critique }
                  - { from: critique, to: draft, when: "output.score < 0.8", loop: true, max_iterations: 3 }
                  - { from: critique, to: finalize }
                """);
        List<List<String>> steps = layerer.computeSuperSteps(def);
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0)).containsExactly("draft");
        assertThat(steps.get(1)).containsExactly("critique");
        assertThat(steps.get(2)).containsExactly("finalize");
    }

    @Test
    @DisplayName("纯静态 DAG（无回边）→ 分层不受影响（回归）")
    void staticDagLayeringUnchanged() {
        WorkflowDefinition def = parser.parse("""
                nodes:
                  - { id: A, agent: a }
                  - { id: B, agent: b }
                  - { id: C, agent: c }
                edges:
                  - { from: A, to: B }
                  - { from: B, to: C }
                """);
        List<List<String>> steps = layerer.computeSuperSteps(def);
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0)).containsExactly("A");
        assertThat(steps.get(1)).containsExactly("B");
        assertThat(steps.get(2)).containsExactly("C");
    }

    @Test
    @DisplayName("回边目标有非回边入边 → 层号由非回边入边决定，不受回边影响")
    void backedgeTargetLayerFromNonBackedgeInput() {
        WorkflowDefinition def = parser.parse("""
                nodes:
                  - { id: start, agent: s }
                  - { id: draft, agent: writer }
                  - { id: critique, agent: reviewer }
                  - { id: finalize, agent: editor }
                edges:
                  - { from: start, to: draft }
                  - { from: draft, to: critique }
                  - { from: critique, to: draft, when: "output.score < 0.8", loop: true, max_iterations: 3 }
                  - { from: critique, to: finalize }
                """);
        List<List<String>> steps = layerer.computeSuperSteps(def);
        assertThat(steps).hasSize(4);
        assertThat(steps.get(0)).containsExactly("start");
        assertThat(steps.get(1)).containsExactly("draft");
        assertThat(steps.get(2)).containsExactly("critique");
        assertThat(steps.get(3)).containsExactly("finalize");
    }
}
