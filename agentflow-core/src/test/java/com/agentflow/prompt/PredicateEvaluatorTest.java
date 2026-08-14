package com.agentflow.prompt;

import com.agentflow.agent.FatalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U2 验证：谓词求值器（SpEL → boolean）+ 求值错误按 Fatal。
 */
class PredicateEvaluatorTest {

    private final PredicateEvaluator evaluator = new PredicateEvaluator();

    @Test
    @DisplayName("字符串相等谓词 → true / false")
    void stringEquality() throws FatalException {
        assertThat(evaluator.evaluate("output.verdict == 'approved'", Map.of("verdict", "approved"))).isTrue();
        assertThat(evaluator.evaluate("output.verdict == 'approved'", Map.of("verdict", "rejected"))).isFalse();
    }

    @Test
    @DisplayName("数值比较谓词 → 正确求值")
    void numericComparison() throws FatalException {
        assertThat(evaluator.evaluate("output.score > 0.5", Map.of("score", 0.8))).isTrue();
        assertThat(evaluator.evaluate("output.score > 0.5", Map.of("score", 0.2))).isFalse();
    }

    @Test
    @DisplayName("类型不匹配 → FatalException（非静默 false）")
    void typeMismatchThrowsFatal() {
        assertThatThrownBy(() -> evaluator.evaluate("output.verdict > 5", Map.of("verdict", "approved")))
                .isInstanceOf(FatalException.class)
                .hasMessageContaining("when");
    }

    @Test
    @DisplayName("T() 类型引用 → FatalException（KTD-2 安全约束）")
    void typeReferenceThrowsFatal() {
        assertThatThrownBy(() -> evaluator.evaluate("T(java.lang.System).exit(0)", Map.of()))
                .isInstanceOf(FatalException.class);
    }

    @Test
    @DisplayName("缺失字段（拼错字段）→ FatalException（MapAccessor 对缺键抛错）")
    void missingFieldThrowsFatal() {
        assertThatThrownBy(() -> evaluator.evaluate("output.missing == 'x'", Map.of("other", "y")))
                .isInstanceOf(FatalException.class)
                .hasMessageContaining("when");
    }

    @Test
    @DisplayName("裸字段结果非 boolean → FatalException")
    void nonBooleanResultThrowsFatal() {
        // verdict 是字符串 "approved"，裸字段求值结果非 boolean
        assertThatThrownBy(() -> evaluator.evaluate("output.verdict", Map.of("verdict", "approved")))
                .isInstanceOf(FatalException.class)
                .hasMessageContaining("boolean");
    }

    @Test
    @DisplayName("null 结果（字段存在但值为 null）→ FatalException（非 boolean）")
    void nullValueResultThrowsFatal() {
        Map<String, Object> output = new HashMap<>();
        output.put("verdict", null);
        assertThatThrownBy(() -> evaluator.evaluate("output.verdict", output))
                .isInstanceOf(FatalException.class)
                .hasMessageContaining("boolean");
    }

    @Test
    @DisplayName("语法错误 → FatalException（ExpressionException 覆盖 ParseException，非裸泄漏）")
    void syntaxErrorThrowsFatal() {
        assertThatThrownBy(() -> evaluator.evaluate("output.verdict ==", Map.of("verdict", "approved")))
                .isInstanceOf(FatalException.class)
                .hasMessageContaining("when");
    }

    @Test
    @DisplayName("context.<channel> 谓词 → 从 channel 扁平视图取值")
    void contextReference() throws FatalException {
        assertThat(evaluator.evaluate("context.riskLevel == 'high'",
                Map.of(), Map.of("riskLevel", "high"), Map.of())).isTrue();
        assertThat(evaluator.evaluate("context.riskLevel == 'high'",
                Map.of(), Map.of("riskLevel", "low"), Map.of())).isFalse();
    }

    @Test
    @DisplayName("inputs.<key> 谓词 → 从工作流入参取值")
    void inputsReference() throws FatalException {
        assertThat(evaluator.evaluate("inputs.verdict == 'approved'",
                Map.of(), Map.of(), Map.of("verdict", "approved"))).isTrue();
    }
}
