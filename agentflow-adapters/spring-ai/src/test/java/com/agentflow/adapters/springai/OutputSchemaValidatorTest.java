package com.agentflow.adapters.springai;

import com.agentflow.agent.FatalException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputSchemaValidatorTest {

    /** 构造 plan U3 示例 schema: {type:object, properties:{riskLevel:{type:string,enum:[LOW,MEDIUM,HIGH]}, debtRatio:{type:number}}, required:[riskLevel]} */
    private static Map<String, Object> riskSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> riskLevel = new LinkedHashMap<>();
        riskLevel.put("type", "string");
        riskLevel.put("enum", List.of("LOW", "MEDIUM", "HIGH"));
        Map<String, Object> debtRatio = new LinkedHashMap<>();
        debtRatio.put("type", "number");
        schema.put("properties", Map.of("riskLevel", riskLevel, "debtRatio", debtRatio));
        schema.put("required", List.of("riskLevel"));
        return schema;
    }

    @Test
    @DisplayName("校验成功：```json 代码块的合法 JSON → structuredOutput 正确填充")
    void validateSuccess() {
        OutputSchemaValidator v = new OutputSchemaValidator();
        String content = "分析完成。```json\n{\"riskLevel\": \"HIGH\", \"debtRatio\": 0.75}\n```";
        OutputSchemaValidator.Result r = v.validate(content, riskSchema());
        assertThat(r.valid()).isTrue();
        assertThat(r.structuredOutput()).containsEntry("riskLevel", "HIGH");
        assertThat(r.structuredOutput()).containsEntry("debtRatio", 0.75);
    }

    @Test
    @DisplayName("校验成功：纯 JSON 文本（无代码块）→ 提取首个 {...}")
    void validateSuccessPureJson() {
        OutputSchemaValidator v = new OutputSchemaValidator();
        String content = "{\"riskLevel\": \"LOW\"}";
        OutputSchemaValidator.Result r = v.validate(content, riskSchema());
        assertThat(r.valid()).isTrue();
        assertThat(r.structuredOutput()).containsEntry("riskLevel", "LOW");
    }

    @Test
    @DisplayName("校验失败：缺 required 字段 → errors 非空")
    void validateFailureMissingRequired() {
        OutputSchemaValidator v = new OutputSchemaValidator();
        String content = "{\"debtRatio\": 0.5}";  // 缺 riskLevel
        OutputSchemaValidator.Result r = v.validate(content, riskSchema());
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).isNotEmpty();
    }

    @Test
    @DisplayName("校验失败：enum 值非法 → errors 非空")
    void validateFailureBadEnum() {
        OutputSchemaValidator v = new OutputSchemaValidator();
        String content = "{\"riskLevel\": \"CRITICAL\"}";  // 不在 enum
        OutputSchemaValidator.Result r = v.validate(content, riskSchema());
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).isNotEmpty();
    }

    @Test
    @DisplayName("重试成功：首次返回无 JSON，重试返回合法 JSON → 返回 structuredOutput")
    void retrySuccess() throws Exception {
        OutputSchemaValidator v = new OutputSchemaValidator();
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Function<String, String> llmCaller = prompt -> {
            int n = calls.incrementAndGet();
            return n == 1 ? "我无法生成 JSON" : "{\"riskLevel\": \"MEDIUM\"}";
        };

        OutputSchemaValidator.ValidatedOutput out = v.validateWithRetry("分析风险", riskSchema(), llmCaller);

        assertThat(out.structuredOutput()).containsEntry("riskLevel", "MEDIUM");
        assertThat(out.content()).contains("MEDIUM");
        assertThat(calls.get()).isEqualTo(2);  // 首次 + 1 次重试
    }

    @Test
    @DisplayName("重试耗尽：3 次尝试全失败 → FatalException")
    void retryExhaustedThrowsFatal() {
        OutputSchemaValidator v = new OutputSchemaValidator();
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Function<String, String> llmCaller = prompt -> {
            calls.incrementAndGet();
            return "no json";  // 永远无 JSON
        };

        assertThatThrownBy(() -> v.validateWithRetry("分析风险", riskSchema(), llmCaller))
                .isInstanceOf(FatalException.class)
                .hasMessageContaining("校验失败 3 次");
        assertThat(calls.get()).isEqualTo(3);  // 首次 + 2 次重试
    }

    @Test
    @DisplayName("重试 prompt 含 schema 与错误信息（让模型可纠正）")
    void retryPromptContainsSchemaAndErrors() throws Exception {
        OutputSchemaValidator v = new OutputSchemaValidator();
        java.util.List<String> prompts = new java.util.ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Function<String, String> llmCaller = prompt -> {
            prompts.add(prompt);
            int n = calls.incrementAndGet();
            return n == 1 ? "no json" : "{\"riskLevel\": \"LOW\"}";
        };

        v.validateWithRetry("原始任务", riskSchema(), llmCaller);

        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(0)).isEqualTo("原始任务");
        String retry = prompts.get(1);
        assertThat(retry).contains("JSON Schema").contains("原始任务");
        // 含 enum/riskLevel 等 schema 内容
        assertThat(retry).contains("riskLevel");
    }

    @Test
    @DisplayName("extractJson：代码块优先，否则首个 {...}，否则原文")
    void extractJsonVariants() {
        assertThat(OutputSchemaValidator.extractJson("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(OutputSchemaValidator.extractJson("text {\"a\":1} tail")).isEqualTo("{\"a\":1}");
        assertThat(OutputSchemaValidator.extractJson("no json here")).isEqualTo("no json here");
        assertThat(OutputSchemaValidator.extractJson("")).isEqualTo("");
        assertThat(OutputSchemaValidator.extractJson(null)).isEqualTo("");
    }
}
