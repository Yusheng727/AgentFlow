package com.agentflow.kafka;

import com.agentflow.api.WorkflowExecutionService;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.engine.checkpoint.WorkflowStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link KafkaWorkflowConsumer} 单元测试（U2，KTD-B/F）。
 *
 * <p>覆盖：非终态执行、终态幂等跳过、执行失败标 FAILED、畸形消息跳过。
 */
class KafkaWorkflowConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private InMemoryCheckpointManager cm;
    private WorkflowExecutionService service;
    private KafkaWorkflowConsumer consumer;

    @BeforeEach
    void setUp() {
        cm = new InMemoryCheckpointManager();
        service = mock(WorkflowExecutionService.class);
        consumer = new KafkaWorkflowConsumer(service, cm, mapper);
    }

    private String payload(String wfId, String name, String version) throws Exception {
        return mapper.writeValueAsString(
                new WorkflowExecutionMessage(wfId, name, version, Map.of("in", 1)));
    }

    @Test
    @DisplayName("非终态消息 → 调 executionService.run 且参数透传")
    void nonTerminalRunsService() throws Exception {
        cm.initWorkflow("wf-1", "wf", "1.0", "caller");
        consumer.onMessage(payload("wf-1", "wf", "1.0"));
        verify(service).run(eq("wf-1"), eq("wf"), eq("1.0"), eq(Map.of("in", 1)));
    }

    @Test
    @DisplayName("已终态消息 → 幂等跳过（重放防护），不调 run")
    void terminalSkipsRun() throws Exception {
        cm.initWorkflow("wf-2", "wf", "1.0", "caller");
        cm.updateStatus("wf-2", WorkflowStatus.SUCCESS);
        consumer.onMessage(payload("wf-2", "wf", "1.0"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("run 抛异常 → 兜底标 FAILED（防 orphan PENDING）")
    void runFailureMarksFailed() throws Exception {
        cm.initWorkflow("wf-3", "wf", "1.0", "caller");
        doThrow(new IllegalStateException("boom")).when(service).run(any(), any(), any(), any());
        consumer.onMessage(payload("wf-3", "wf", "1.0"));
        assertThat(cm.findStatus("wf-3")).contains(WorkflowStatus.FAILED);
    }

    @Test
    @DisplayName("畸形 JSON → 跳过不执行")
    void malformedPayloadSkipped() {
        consumer.onMessage("not-json");
        verifyNoInteractions(service);
    }
}
