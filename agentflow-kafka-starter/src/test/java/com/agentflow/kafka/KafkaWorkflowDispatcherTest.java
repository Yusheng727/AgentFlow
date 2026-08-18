package com.agentflow.kafka;

import com.agentflow.api.WorkflowDispatchRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link KafkaWorkflowDispatcher} 单元测试（U2，KTD-C/D）。
 *
 * <p>验证：dispatch 把 WorkflowDispatchRequest 序列化成 JSON 字符串发到正确 topic/key。
 */
class KafkaWorkflowDispatcherTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("dispatch 序列化 WorkflowExecutionMessage 并发送到 topic（key=workflowId）")
    void dispatchSerializesAndSends() throws Exception {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        KafkaWorkflowDispatcher dispatcher = new KafkaWorkflowDispatcher(template, mapper);

        dispatcher.dispatch(new WorkflowDispatchRequest("wf-1", "wf", "1.0", Map.of("k", "中文值")));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq("agentflow.workflow.executions"), eq("wf-1"), payload.capture());
        WorkflowExecutionMessage msg = mapper.readValue(payload.getValue(), WorkflowExecutionMessage.class);
        assertThat(msg.workflowId()).isEqualTo("wf-1");
        assertThat(msg.workflowName()).isEqualTo("wf");
        assertThat(msg.version()).isEqualTo("1.0");
        assertThat(msg.inputs()).containsEntry("k", "中文值");
    }
}
