package com.agentflow.dsl;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * YAML DSL 解析器（第一层校验：Jackson 类型映射）。
 *
 * <p>SNAKE_CASE 命名策略：YAML {@code prompt_template} ↔ Java {@code promptTemplate}；
 * ACCEPT_CASE_INSENSITIVE_ENUMS：{@code reducer: overwrite} ↔ {@link Reducer#OVERWRITE}。
 * 类型错误 / 缺少必填字段在此层抛 {@link WorkflowValidationException}。
 */
public class WorkflowDSLParser {

    private final ObjectMapper mapper;

    public WorkflowDSLParser() {
        this.mapper = YAMLMapper.builder(new YAMLFactory())
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .build();
    }

    public WorkflowDefinition parse(InputStream yaml) {
        try {
            WorkflowDefinition def = mapper.readValue(yaml, WorkflowDefinition.class);
            // null 字段兜底，避免后续 NPE
            return def;
        } catch (JacksonException e) {
            // 类型错误 / 缺少必填字段
            throw new WorkflowValidationException(
                    "YAML 类型映射失败: " + e.getLocation() + " — " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new WorkflowValidationException("YAML 读取失败: " + e.getMessage(), e);
        }
    }

    public WorkflowDefinition parse(String yaml) {
        return parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
