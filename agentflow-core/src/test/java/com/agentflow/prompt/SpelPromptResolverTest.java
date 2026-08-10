package com.agentflow.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.SpelEvaluationException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SpelPromptResolver 单元测试（v1.1 下沉 core 的复用件）。
 *
 * <p>覆盖：占位符替换、嵌套点路径、inputs 引用、null 值置空串、安全约束（T() 禁反射）。
 */
class SpelPromptResolverTest {

    private final SpelPromptResolver resolver = new SpelPromptResolver();

    @Test
    @DisplayName("无占位符模板原样返回")
    void noPlaceholderReturnsAsIs() {
        assertThat(resolver.resolve("纯文本模板", Map.of(), Map.of())).isEqualTo("纯文本模板");
    }

    @Test
    @DisplayName("null/空模板 → 空串")
    void nullTemplateReturnsEmpty() {
        assertThat(resolver.resolve(null, Map.of(), Map.of())).isEqualTo("");
        assertThat(resolver.resolve("", Map.of(), Map.of())).isEqualTo("");
    }

    @Test
    @DisplayName("${context.x} 从 channel 扁平视图取值")
    void contextPlaceholderResolves() {
        String out = resolver.resolve("分析 ${context.company}", Map.of("company", "Acme"), Map.of());
        assertThat(out).isEqualTo("分析 Acme");
    }

    @Test
    @DisplayName("嵌套点路径 ${context.finance.riskScore} 下钻 Map")
    void nestedDotPathResolves() {
        Map<String, Object> channels = Map.of("finance", Map.of("riskScore", "HIGH"));
        String out = resolver.resolve("风险=${context.finance.riskScore}", channels, Map.of());
        assertThat(out).isEqualTo("风险=HIGH");
    }

    @Test
    @DisplayName("${inputs.supplier} 从工作流入参取值")
    void inputsPlaceholderResolves() {
        String out = resolver.resolve("供应商=${inputs.supplier}", Map.of(), Map.of("supplier", "Acme Corp"));
        assertThat(out).isEqualTo("供应商=Acme Corp");
    }

    @Test
    @DisplayName("占位符求值 null → 替换为空串（不输出字面 null）")
    void nullValueBecomesEmptyString() {
        Map<String, Object> channels = new java.util.HashMap<>();
        channels.put("note", null);
        String out = resolver.resolve("A${context.note}B", channels, Map.of());
        assertThat(out).isEqualTo("AB");
    }

    @Test
    @DisplayName("缺失键（MapAccessor 语义）→ 抛 SpelEvaluationException（由适配器映射 Fatal）")
    void missingKeyThrows() {
        assertThatThrownBy(() -> resolver.resolve("${context.missing}", Map.of(), Map.of()))
                .isInstanceOf(SpelEvaluationException.class);
    }

    @Test
    @DisplayName("安全约束（KTD-2）：${T(java.lang.System).exit(0)} 抛 SpelEvaluationException，不执行")
    void typeReferenceBlocked() {
        assertThatThrownBy(() ->
                resolver.resolve("${T(java.lang.System).exit(0)}", Map.of(), Map.of()))
                .isInstanceOf(SpelEvaluationException.class);
    }
}
