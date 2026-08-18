package com.agentflow.kafka;

import java.util.Map;

/**
 * Kafka 工作流执行消息（KTD-A）：只携带身份 + 入参，定义由消费者按 (workflowName, version) 重取。
 *
 * <p>wire 格式 = JSON 字符串（KTD-D）——StringSerializer + 项目自持 ObjectMapper（
 * {@code agentflowKafkaObjectMapper}）手动 {@code writeValueAsString}/{@code readValue}；
 * 不再使用已废弃的 JsonSerializer/JsonDeserializer + 类型头。
 */
public record WorkflowExecutionMessage(
        String workflowId,
        String workflowName,
        String version,
        Map<String, Object> inputs
) {}
