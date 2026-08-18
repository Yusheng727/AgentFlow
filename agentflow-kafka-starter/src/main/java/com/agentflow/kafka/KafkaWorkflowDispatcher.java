package com.agentflow.kafka;

import com.agentflow.api.WorkflowDispatchRequest;
import com.agentflow.api.WorkflowDispatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.kafka.core.KafkaTemplate;

import java.util.Objects;

/**
 * Kafka 派发（KTD-C，v1.1）：把提交消息发到 topic，消费者拉取后执行——提交/执行经 Kafka 解耦。
 *
 * <p>装配由 {@code agentflow.kafka.enabled} 门控（KTD-C），未启用时 WorkflowController 回落本地
 * {@link com.agentflow.api.LocalVirtualThreadDispatcher}。wire 格式 = JSON 字符串（StringSerializer
 * + 项目 ObjectMapper，KTD-D），避开 spring-kafka 4.x 废弃的 JsonSerializer。
 */
public class KafkaWorkflowDispatcher implements WorkflowDispatcher {

    public static final String TOPIC = "agentflow.workflow.executions";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper;

    public KafkaWorkflowDispatcher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper mapper) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void dispatch(WorkflowDispatchRequest request) {
        WorkflowExecutionMessage message = new WorkflowExecutionMessage(
                request.workflowId(), request.workflowName(), request.version(), request.inputs());
        try {
            kafkaTemplate.send(TOPIC, message.workflowId(), mapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化工作流执行消息失败 wf=" + message.workflowId(), e);
        }
    }
}
