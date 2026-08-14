package com.agentflow.dsl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U3 验证：条件边（when）+ on_error 的静态校验。
 */
class SemanticValidatorTest {

    private WorkflowDSLParser parser;
    private SemanticValidator validator;

    @BeforeEach
    void setUp() {
        parser = new WorkflowDSLParser();
        validator = new SemanticValidator();
    }

    private WorkflowDefinition parseAndValidate(String yaml) {
        WorkflowDefinition def = parser.parse(yaml);
        validator.validate(def);
        return def;
    }

    @Test
    @DisplayName("on_error 指向上游节点 → 成环拒绝")
    void onErrorUpstreamRejected() {
        assertThatThrownBy(() -> parseAndValidate("""
                nodes:
                  - { id: A, agent: a }
                  - { id: B, agent: b, on_error: A }
                edges:
                  - { from: A, to: B }
                """))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("环路");
    }

    @Test
    @DisplayName("on_error 自环 → 成环拒绝")
    void onErrorSelfLoopRejected() {
        assertThatThrownBy(() -> parseAndValidate("""
                nodes:
                  - { id: A, agent: a, on_error: A }
                """))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("环路");
    }

    @Test
    @DisplayName("on_error 目标不存在 → 拒绝")
    void onErrorMissingTargetRejected() {
        assertThatThrownBy(() -> parseAndValidate("""
                nodes:
                  - { id: A, agent: a, on_error: Z }
                """))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("on_error");
    }

    @Test
    @DisplayName("路由节点（有 when 边）多条默认边 → 拒绝")
    void routingNodeMultipleDefaultEdgesRejected() {
        assertThatThrownBy(() -> parseAndValidate("""
                nodes:
                  - { id: A, agent: a }
                  - { id: B, agent: b }
                  - { id: C, agent: c }
                  - { id: D, agent: d }
                edges:
                  - { from: A, to: B, when: "output.x == 1" }
                  - { from: A, to: C }
                  - { from: A, to: D }
                """))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("默认边");
    }

    @Test
    @DisplayName("合法条件边 + on_error → 通过")
    void validConditionalAndOnErrorAccepted() {
        assertThatCode(() -> parseAndValidate("""
                nodes:
                  - { id: A, agent: a, on_error: cleanup }
                  - { id: B, agent: b }
                  - { id: C, agent: c }
                  - { id: cleanup, agent: d }
                edges:
                  - { from: A, to: B, when: "output.x == 1" }
                  - { from: A, to: C }
                """)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("on_error 指向纯 cleanup 节点（无正常入边）→ 通过（不误拒）")
    void onErrorCleanupNodeAccepted() {
        assertThatCode(() -> parseAndValidate("""
                nodes:
                  - { id: A, agent: a, on_error: cleanup }
                  - { id: B, agent: b }
                  - { id: cleanup, agent: d }
                edges:
                  - { from: A, to: B }
                """)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("纯 fan-out（无 when 边，多条无条件边）→ 通过（v1 行为不回归）")
    void pureFanOutAccepted() {
        assertThatCode(() -> parseAndValidate("""
                nodes:
                  - { id: A, agent: a }
                  - { id: B, agent: b }
                  - { id: C, agent: c }
                edges:
                  - { from: A, to: B }
                  - { from: A, to: C }
                """)).doesNotThrowAnyException();
    }
}
