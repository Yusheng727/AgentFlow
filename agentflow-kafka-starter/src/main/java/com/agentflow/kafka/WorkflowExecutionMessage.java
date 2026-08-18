package com.agentflow.kafka;

import java.util.Map;

/**
 * Kafka 工作流执行消息（KTD-A）：只携带身份 + 入参，定义由消费者按 (workflowName, version) 重取。
 *
 * <p>经 JsonSerializer/JsonDeserializer（配置项目 ObjectMapper，KTD-D）+ 类型头传输。
 */
public record WorkflowExecutionMessage(
        String workflowId,
        String workflowName,
        String version,
        Map<String, Object> inputs
) {}
