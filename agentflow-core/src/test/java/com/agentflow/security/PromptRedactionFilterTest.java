package com.agentflow.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U14 PromptRedactionFilter 测试（plan U14 Test scenarios）。
 */
class PromptRedactionFilterTest {

    @Test
    @DisplayName("OpenAI API Key 脱敏：sk-... → sk-***")
    void redactsOpenAiKey() {
        String input = "Authorization: Bearer sk-proj-abc123def456ghi789jkl012mno345pqr678stu";
        String result = PromptRedactionFilter.redact(input);
        assertThat(result).doesNotContain("abc123def");
        assertThat(result).contains("sk-***");
    }

    @Test
    @DisplayName("非 sk- 前缀的 Key（如标准 sk-...）脱敏")
    void redactsStandardSkKey() {
        String input = "使用 API Key: sk-1234567890abcdef1234567890abcdef12345678";
        String result = PromptRedactionFilter.redact(input);
        assertThat(result).doesNotContain("1234567890abcdef");
        assertThat(result).contains("sk-***");
    }

    @Test
    @DisplayName("Bearer Token 脱敏：Bearer eyJ... → Bearer ***")
    void redactsBearerToken() {
        String input = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";
        String result = PromptRedactionFilter.redact(input);
        assertThat(result).doesNotContain("eyJhbGci");
        assertThat(result).contains("Bearer ***");
    }

    @Test
    @DisplayName("中国手机号脱敏：13812345678 → 138****5678")
    void redactsMobilePhone() {
        String input = "联系电话：13812345678，备用：15900001111";
        String result = PromptRedactionFilter.redact(input);
        assertThat(result).contains("138****5678");
        assertThat(result).contains("159****1111");
        assertThat(result).doesNotContain("13812345678");
    }

    @Test
    @DisplayName("中国身份证号脱敏：110101199001011234 → 110101********1234")
    void redactsIdCard() {
        String input = "身份证号：110101199001011234";
        String result = PromptRedactionFilter.redact(input);
        assertThat(result).contains("110101********1234");
        assertThat(result).doesNotContain("19900101");
    }

    @Test
    @DisplayName("混合内容：多种敏感信息同时脱敏")
    void redactsMixedContent() {
        String input = """
                System Prompt:
                用户手机：13987654321
                使用 API Key: sk-abcdefghij1234567890klmnopqrstuv
                Authorization: Bearer eyJtoken1234567890abcdef
                """;
        String result = PromptRedactionFilter.redact(input);

        assertThat(result).doesNotContain("13987654321");
        assertThat(result).doesNotContain("abcdefghij");
        assertThat(result).doesNotContain("eyJtoken");
        assertThat(result).contains("139****4321");
        assertThat(result).contains("sk-***");
        assertThat(result).contains("Bearer ***");
    }

    @Test
    @DisplayName("无敏感信息 → 原样返回")
    void noSensitiveDataReturnsOriginal() {
        String input = "请分析以下供应商的财务数据：公司A，年度收入500万。";
        String result = PromptRedactionFilter.redact(input);
        assertThat(result).isEqualTo(input);
    }

    @Test
    @DisplayName("null / 空字符串 → 原样返回")
    void nullOrEmptyReturnsAsIs() {
        assertThat(PromptRedactionFilter.redact(null)).isNull();
        assertThat(PromptRedactionFilter.redact("")).isEmpty();
        assertThat(PromptRedactionFilter.redact("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("redactMap 递归脱敏 Map 中所有 String value")
    void redactMapRecursively() {
        Map<String, Object> input = Map.of(
                "prompt", "手机：13800001111",
                "metadata", Map.of("key", "sk-1234567890abcdef123456"),
                "count", 42
        );

        Map<String, Object> result = PromptRedactionFilter.redactMap(input);

        assertThat(result.get("prompt")).asString().contains("138****1111");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) result.get("metadata");
        assertThat(meta.get("key")).asString().contains("sk-***");
        assertThat(result.get("count")).isEqualTo(42); // 非 String 不变
    }
}