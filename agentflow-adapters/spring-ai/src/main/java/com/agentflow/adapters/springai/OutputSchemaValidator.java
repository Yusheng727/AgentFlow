package com.agentflow.adapters.springai;

import com.agentflow.agent.FatalException;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 输出 JSON Schema 校验器（U3-5，plan I1）。
 *
 * <p>工作流（plan U3）：
 * <ol>
 *   <li>从 LLM 文本响应中提取 JSON（```json 代码块 或 首个 {...} 段）</li>
 *   <li>用 networknt json-schema-validator 3.x（Jackson 3 兼容，Spring AI 传递依赖）校验</li>
 *   <li>校验失败 → 构造"带反馈的重试 prompt"（附 schema + 错误），让模型重生</li>
 *   <li>最多重试 2 次（共 3 次尝试），仍失败抛 {@link FatalException}</li>
 * </ol>
 *
 * <p><b>retry 预算（plan v4.2）</b>：U3 先实现 schema-retry ≤2 次；U4 的 RetryPolicy 将把
 * schema-retry 嵌进一次 attempt 内，共享 per-node LLM 调用总预算 max 3 次（封顶 3×(1+2)=9）。
 *
 * <p>networknt 3.x API（KTD-7 冒烟后定）：{@code SchemaRegistry.withDefaultDialect(V2020_12)}
 * → {@code getSchema(schemaJson)} → {@code schema.validate(json, InputFormat.JSON)} → {@code List<Error>}。
 */
public class OutputSchemaValidator {

    /** schema-retry 上限（plan v4.2：不含首次共 2 次重试） */
    static final int MAX_SCHEMA_RETRIES = 2;

    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*(.+?)\\s*```", Pattern.DOTALL);

    private final SchemaRegistry schemaRegistry;
    private final JsonMapper jsonMapper;

    public OutputSchemaValidator() {
        this.schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        this.jsonMapper = new JsonMapper();
    }

    /**
     * 单次校验：提取 JSON → 校验 → 返回结果。
     *
     * @param content LLM 文本响应
     * @param schema  节点 output_schema（Map 形式，从 NodeDefinition 透传）
     * @return 校验结果（valid + structuredOutput 或 errors）
     */
    public Result validate(String content, Map<String, Object> schema) {
        Objects.requireNonNull(schema, "schema");
        String json = extractJson(content);
        String schemaJson = writeJson(schema);
        Schema sch = schemaRegistry.getSchema(schemaJson);
        List<Error> errors;
        try {
            errors = sch.validate(json, InputFormat.JSON);
        } catch (RuntimeException e) {
            // 输入非合法 JSON（networknt 3.x 抛 StreamReadException 而非返回 error）→ 视为校验失败
            return new Result(false, Map.of(), List.of("非法 JSON 输入: " + e.getMessage()));
        }
        if (errors == null || errors.isEmpty()) {
            Map<String, Object> structured = parseJsonToMap(json);
            return new Result(true, structured, List.of());
        }
        List<String> msgs = new ArrayList<>();
        for (Error e : errors) {
            msgs.add(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        return new Result(false, Map.of(), msgs);
    }

    /**
     * 带重试的校验：调用 llmCaller 获取 LLM 响应，校验失败则用反馈 prompt 重调。
     *
     * @param initialPrompt 原始 prompt（已 SpEL 解析）
     * @param schema        节点 output_schema
     * @param llmCaller     调用 LLM 的函数（prompt → content），由适配器注入
     * @return 校验通过的最终内容 + 结构化输出 Map
     * @throws FatalException 重试耗尽仍失败
     */
    public ValidatedOutput validateWithRetry(String initialPrompt, Map<String, Object> schema,
                                             Function<String, String> llmCaller) throws FatalException {
        Objects.requireNonNull(initialPrompt, "initialPrompt");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(llmCaller, "llmCaller");
        String prompt = initialPrompt;
        String schemaJson = writeJson(schema);
        for (int attempt = 0; attempt <= MAX_SCHEMA_RETRIES; attempt++) {
            String content = llmCaller.apply(prompt);
            Result r = validate(content, schema);
            if (r.valid()) {
                return new ValidatedOutput(content, r.structuredOutput());
            }
            if (attempt == MAX_SCHEMA_RETRIES) {
                throw new FatalException("Output schema 校验失败 " + (attempt + 1) + " 次: " + r.errors());
            }
            prompt = buildRetryPrompt(initialPrompt, schemaJson, r.errors());
        }
        throw new IllegalStateException("unreachable");
    }

    /** 从 LLM 文本提取 JSON：优先 ```json 代码块，否则首个 { 到末个 }。 */
    static String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        Matcher m = JSON_FENCE.matcher(content);
        if (m.find()) {
            return m.group(1).trim();
        }
        int first = content.indexOf('{');
        int last = content.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return content.substring(first, last + 1).trim();
        }
        return content.trim();
    }

    private String buildRetryPrompt(String originalPrompt, String schemaJson, List<String> errors) {
        return "你的上一次回复不符合期望的 JSON Schema，请重新生成严格符合 schema 的 JSON。\n"
                + "期望 JSON Schema:\n" + schemaJson + "\n"
                + "校验错误:\n- " + String.join("\n- ", errors) + "\n"
                + "原始任务:\n" + originalPrompt;
    }

    private String writeJson(Object obj) {
        try {
            return jsonMapper.writeValueAsString(obj);
        } catch (RuntimeException e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonToMap(String json) {
        try {
            return jsonMapper.readValue(json, Map.class);
        } catch (RuntimeException e) {
            // 校验已通过但解析失败——理论上不该发生，作 IllegalStateException 兜底
            throw new IllegalStateException("校验通过的 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /** 单次校验结果。 */
    public record Result(boolean valid, Map<String, Object> structuredOutput, List<String> errors) {
    }

    /** 带重试校验的最终结果：通过校验的内容 + 结构化输出。 */
    public record ValidatedOutput(String content, Map<String, Object> structuredOutput) {
    }
}
