package com.agentflow.observability;

import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化日志（U6，R11）。
 *
 * <p>每条 Agent 执行输出一行 JSON 格式的日志，字段：workflowId, nodeId, agentName, durationMs,
 * token（promptTokens + completionTokens），status（SUCCESS / FAILED），outputSummary。
 *
 * <p>用法：在 {@code SpringAiAgentAdapter} 或其他 AgentFunction 实现中，节点完成时调用
 * {@link #logSuccess} / {@link #logFailure} 即可输出结构化 JSON。
 */
public final class StructuredLogger {

    private static final Logger log = LoggerFactory.getLogger("agentflow.execution");

    private StructuredLogger() {
    }

    /** 节点执行成功时记录。 */
    public static void logSuccess(String workflowId, String nodeId, String agentName,
                                   Duration duration, long promptTokens, long completionTokens,
                                   String outputSummary) {
        Map<String, Object> fields = baseFields(workflowId, nodeId, agentName, duration);
        fields.put("status", "SUCCESS");
        fields.put("promptTokens", promptTokens);
        fields.put("completionTokens", completionTokens);
        fields.put("totalTokens", promptTokens + completionTokens);
        if (outputSummary != null) {
            fields.put("outputSummary", truncate(outputSummary, 200));
        }
        log.info(toJson(fields));
    }

    /** 节点执行失败时记录。 */
    public static void logFailure(String workflowId, String nodeId, String agentName,
                                   Duration duration, String error) {
        Map<String, Object> fields = baseFields(workflowId, nodeId, agentName, duration);
        fields.put("status", "FAILED");
        if (error != null) {
            fields.put("error", truncate(error, 200));
        }
        log.warn(toJson(fields));
    }

    private static Map<String, Object> baseFields(String workflowId, String nodeId,
                                                   String agentName, Duration duration) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("timestamp", Instant.now().toString());
        fields.put("workflowId", workflowId);
        fields.put("nodeId", nodeId);
        fields.put("agentName", agentName);
        fields.put("durationMs", duration.toMillis());
        return fields;
    }

    /** 手动构建扁平 JSON 对象（避免引入 Jackson/ObjectMapper 依赖）。 */
    private static String toJson(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : fields.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append('"').append(entry.getKey()).append("\": ");
            Object value = entry.getValue();
            if (value instanceof String s) {
                sb.append('"').append(escape(s)).append('"');
            } else if (value instanceof Number) {
                sb.append(value);
            } else {
                sb.append('"').append(escape(String.valueOf(value))).append('"');
            }
            first = false;
        }
        return sb.append("}").toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
