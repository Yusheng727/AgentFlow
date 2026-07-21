package com.agentflow.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 敏感数据脱敏过滤器（R22）。
 *
 * <p>对 prompt/response 文本做正则脱敏后再写入 ExecutionTrace/结构化日志。
 * 本类为无状态工具——core 层不依赖 Spring AI Advisor API；adapter 层将其包装为
 * {@code PromptRedactionAdvisor} 注入 Advisor Chain，在 LoggingAdvisor 前执行。
 *
 * <h3>脱敏模式</h3>
 * <ul>
 *   <li>OpenAI API Key：{@code sk-...} → {@code sk-***}</li>
 *   <li>Bearer Token：{@code Bearer eyJ...} → {@code Bearer ***}</li>
 *   <li>中国手机号：{@code 13812345678} → {@code 138****5678}</li>
 *   <li>中国身份证号：{@code 110101199001011234} → {@code 110101********1234}</li>
 * </ul>
 *
 * <p>线程安全：所有 Pattern 为 static final。
 */
public final class PromptRedactionFilter {

    private PromptRedactionFilter() { /* utility */ }

    // ──────────────────────────── 脱敏模式 ────────────────────────────

    /** OpenAI / 类 OpenAI API Key：sk- 或 sk-proj- 开头 + 字母数字连字符 */
    private static final Pattern OPENAI_KEY = Pattern.compile(
            "\\b(sk-(?:proj-)?[A-Za-z0-9\\-_]{20,})\\b");

    /** Bearer Token：Bearer 后接 JWT 或 base64 */
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "\\b(Bearer\\s+)([A-Za-z0-9+\\/=_\\-]{20,})(\\b)?");

    /** 中国手机号（1[3-9]xxxxxxxxx） */
    private static final Pattern CN_MOBILE = Pattern.compile(
            "\\b(1[3-9]\\d)(\\d{4})(\\d{4})\\b");

    /** 中国身份证号（18 位，最后一位可 X） */
    private static final Pattern CN_ID = Pattern.compile(
            "\\b(\\d{6})(\\d{8})(\\d{3}[0-9Xx])\\b");

    /** 脱敏替换表（有序，先匹配的更具体模式优先）。 */
    private static final LinkedHashMap<Pattern, String> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put(OPENAI_KEY, "sk-***");
        PATTERNS.put(BEARER_TOKEN, "$1***$3");
        PATTERNS.put(CN_MOBILE, "$1****$3");
        PATTERNS.put(CN_ID, "$1********$3");
    }

    // ──────────────────────────── 公共 API ────────────────────────────

    /** 对文本执行全部脱敏模式，返回脱敏后的文本。 */
    public static String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = text;
        for (Map.Entry<Pattern, String> entry : PATTERNS.entrySet()) {
            result = entry.getKey().matcher(result).replaceAll(entry.getValue());
        }
        return result;
    }

    /** 对 Map 中所有 String value 递归脱敏（不修改原 Map，返回新 Map）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> redactMap(Map<String, Object> map) {
        if (map == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            result.put(e.getKey(), redactValue(e.getValue()));
        }
        return Map.copyOf(result);
    }

    private static Object redactValue(Object value) {
        if (value instanceof String s) {
            return redact(s);
        }
        if (value instanceof Map<?, ?> m) {
            return redactMap((Map<String, Object>) m);
        }
        return value;
    }
}