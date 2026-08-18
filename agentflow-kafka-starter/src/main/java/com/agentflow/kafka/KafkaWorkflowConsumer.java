package com.agentflow.kafka;

import com.agentflow.api.WorkflowExecutionService;
import com.agentflow.engine.checkpoint.CheckpointManager;
import com.agentflow.engine.checkpoint.WorkflowStatus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

import java.util.Objects;
import java.util.Optional;

/**
 * Kafka 工作流执行消费者（KTD-B/F）：收到 JSON 消息反序列化后复用 {@link WorkflowExecutionService#run} 执行。
 *
 * <p><b>幂等（KTD-F）</b>：@KafkaListener at-least-once 投递，重放/重复消息先查 checkpoint，
 * 工作流已是终态（SUCCESS/FAILED）则跳过——防重复执行、重复 LLM 计费。
 * <b>防 orphan PENDING</b>：run() 抛出的非引擎异常（如定义缺失）兜底标 FAILED。
 */
public class KafkaWorkflowConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaWorkflowConsumer.class);

    private final WorkflowExecutionService executionService;
    private final CheckpointManager checkpointManager;
    private final ObjectMapper mapper;

    public KafkaWorkflowConsumer(WorkflowExecutionService executionService,
                                 CheckpointManager checkpointManager,
                                 ObjectMapper mapper) {
        this.executionService = Objects.requireNonNull(executionService, "executionService");
        this.checkpointManager = Objects.requireNonNull(checkpointManager, "checkpointManager");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @KafkaListener(topics = KafkaWorkflowDispatcher.TOPIC,
            containerFactory = "agentflowKafkaListenerContainerFactory")
    public void onMessage(String payload) {
        WorkflowExecutionMessage message;
        try {
            message = mapper.readValue(payload, WorkflowExecutionMessage.class);
        } catch (JsonProcessingException e) {
            log.error("反序列化 Kafka 消息失败，跳过（重放会重投）", e);
            return;
        }

        Optional<WorkflowStatus> status = checkpointManager.findStatus(message.workflowId());
        // 只执行「已 staged」的工作流（initWorkflow 写 PENDING，仅受信 REST submit 产生）：
        // 从未 initWorkflow 的任意 id 不执行——防 ledger 污染 + 越 trust boundary 的成本放大
        // （security review P2：生产必须 topic ACL+SASL_SSL，见 KTD-F）。
        if (status.isEmpty()) {
            log.error("丢弃未 staged 的工作流消息（无 initWorkflow 记录）wf={}", message.workflowId());
            return;
        }
        if (isTerminal(status.get())) {
            log.info("跳过已终态工作流（重放防护）wf={} status={}", message.workflowId(), status.get());
            return;
        }
        try {
            executionService.run(message.workflowId(), message.workflowName(),
                    message.version(), message.inputs());
        } catch (Exception e) {
            // run() 引擎失败已标 FAILED；此处兜底定义缺失等异常 → FAILED（防 orphan PENDING）
            log.error("Kafka 消费者执行失败 wf={} name={}", message.workflowId(), message.workflowName(), e);
            checkpointManager.updateStatus(message.workflowId(), WorkflowStatus.FAILED);
        }
    }

    private static boolean isTerminal(WorkflowStatus s) {
        return s == WorkflowStatus.SUCCESS || s == WorkflowStatus.FAILED;
    }
}
