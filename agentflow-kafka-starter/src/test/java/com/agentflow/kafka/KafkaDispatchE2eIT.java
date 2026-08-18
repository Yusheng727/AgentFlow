package com.agentflow.kafka;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.api.WorkflowDispatchRequest;
import com.agentflow.api.WorkflowDispatcher;
import com.agentflow.api.WorkflowExecutionService;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.checkpoint.CheckpointManager;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.engine.checkpoint.NodeOutputStore;
import com.agentflow.engine.checkpoint.WorkflowStatus;
import com.agentflow.version.InMemoryWorkflowDefinitionStore;
import com.agentflow.version.WorkflowVersionManager;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka 提交/执行端到端集成测试（U3，Failsafe {@code *IT}，真 Kafka）。
 *
 * <p>真实链路：{@code KafkaWorkflowDispatcher → topic → KafkaWorkflowConsumer → WorkflowExecutionService
 * → BspEngine → SUCCESS}（单 JVM，producer/consumer 同应用）。用 {@code assumeTrue} 门控：
 * 本地/CI 无 Kafka 时整类跳过，有 {@code apache/kafka:3.9.2}（{@code --profile distributed}）时实跑。
 *
 * <p>context 用 {@code @EnableAutoConfiguration}（排除 DataSource 两个 auto-config）经
 * {@code AutoConfiguration.imports} 装配 KafkaAgentFlowAutoConfiguration——
 * 含 Boot 的 KafkaAutoConfiguration（懒连接无碍），不含 web/JDBC 等无关树。消费端设
 * {@code auto-offset-reset=earliest}：同 JVM 内 dispatch 先 produce、消费者后订阅（新 group 无已提交
 * offset），默认 {@code latest} 会从分区末端起读而错过消息——earliest 消除该竞态（KTD-F 语义：工作流
 * 提交任务不应被跳过）。
 *
 * @see PostgresCheckpointManagerIT Failsafe + assumeTrue 门控先例
 */
@SpringBootTest(classes = KafkaDispatchE2eIT.KafkaE2eContext.class,
        properties = {
                "agentflow.kafka.enabled=true",
                "spring.kafka.bootstrap-servers=localhost:9092",
                "spring.kafka.consumer.auto-offset-reset=earliest",
        })
class KafkaDispatchE2eIT {

    /** AE1：2 节点串行 mock 工作流（step1 → step2，mock_response 静态，无跨节点引用，最小失败面）。 */
    private static final String TWO_NODE_YAML = """
            agentflow:
              version: "1.0"
            nodes:
              - id: draft
                agent: mock
                prompt_template: "write draft"
                mock_response: "draft content"
              - id: finalize
                agent: mock
                prompt_template: "finalize draft"
                mock_response: "final content"
            edges:
              - { from: draft, to: finalize }
            """;

    @Autowired
    private WorkflowDispatcher dispatcher;

    @Autowired
    private CheckpointManager checkpointManager;

    @Autowired
    private WorkflowVersionManager versionManager;

    @Autowired
    private WorkflowDSLParser parser;

    @Autowired
    private AtomicInteger executionCounter;

    @BeforeAll
    static void assumeKafka() {
        Assumptions.assumeTrue(kafkaReachable(),
                "Kafka localhost:9092 不可达（未起 docker compose --profile distributed），跳过集成测试");
    }

    @Test
    @DisplayName("AE1: dispatch → Kafka → consumer → BspEngine → SUCCESS（2 节点串行均执行）")
    void dispatchThenExecutesToSuccess() throws Exception {
        // 装配证明：WorkflowDispatcher 是 Kafka 实现（防止误走本地回退仍全绿——correctness review）
        assertThat(dispatcher).isInstanceOf(KafkaWorkflowDispatcher.class);

        String wfId = newWf("e2e-success");
        recordDefinition("e2e-success");

        dispatcher.dispatch(new WorkflowDispatchRequest(wfId, "e2e-success", "1.0", Map.of()));

        assertThat(awaitTerminal(wfId)).isEqualTo(WorkflowStatus.SUCCESS);
        // 两个串行 super-step 各 1 个节点已执行（checkpoint 有节点输出）
        assertThat(completedNodes(wfId, 0)).hasSize(1);
        assertThat(completedNodes(wfId, 1)).hasSize(1);
        // 节点真实执行（非仅状态翻转）：draft + finalize 各记一次
        assertThat(executionCounter.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("AE4: 终态后重放同一 workflowId → 消费者幂等跳过，引擎不重跑")
    void replayDoesNotReRun() throws Exception {
        String wfId = newWf("e2e-replay");
        recordDefinition("e2e-replay");

        dispatcher.dispatch(new WorkflowDispatchRequest(wfId, "e2e-replay", "1.0", Map.of()));
        assertThat(awaitTerminal(wfId)).isEqualTo(WorkflowStatus.SUCCESS);

        int before = executionCounter.get();
        // 重放同一工作流（应被 KTD-F 终态跳过）
        dispatcher.dispatch(new WorkflowDispatchRequest(wfId, "e2e-replay", "1.0", Map.of()));
        // barrier 工作流：单分区 FIFO，其 SUCCESS 证明重放消息已被消费者处理（幂等跳过），
        // 而非靠固定 sleep 赌消息 round-trip 窗口
        String barrierId = newWf("e2e-replay-barrier");
        recordDefinition("e2e-replay-barrier");
        dispatcher.dispatch(new WorkflowDispatchRequest(barrierId, "e2e-replay-barrier", "1.0", Map.of()));
        assertThat(awaitTerminal(barrierId)).isEqualTo(WorkflowStatus.SUCCESS);

        // barrier 恰好执行 2 节点；若重放被错误重跑，计数会是 before + 4
        assertThat(executionCounter.get()).as("重放不应触发引擎重跑（barrier 已证明重放被消费）")
                .isEqualTo(before + 2);
    }

    @Test
    @DisplayName("连续 dispatch 两个工作流 → 均到终态（消费者多消息处理）")
    void twoWorkflowsSequentially() throws Exception {
        recordDefinition("e2e-two-a");
        recordDefinition("e2e-two-b");
        String wfA = newWf("e2e-two-a");
        String wfB = newWf("e2e-two-b");

        dispatcher.dispatch(new WorkflowDispatchRequest(wfA, "e2e-two-a", "1.0", Map.of()));
        dispatcher.dispatch(new WorkflowDispatchRequest(wfB, "e2e-two-b", "1.0", Map.of()));

        assertThat(awaitTerminal(wfA)).isEqualTo(WorkflowStatus.SUCCESS);
        assertThat(awaitTerminal(wfB)).isEqualTo(WorkflowStatus.SUCCESS);
    }

    // ──────────────────────── 辅助 ────────────────────────

    private String newWf(String name) {
        String wfId = UUID.randomUUID().toString();
        // 镜像生产 submit：先 initWorkflow（写 PENDING + 元数据）再 dispatch
        checkpointManager.initWorkflow(wfId, name, "1.0", "it-caller");
        return wfId;
    }

    /** 按 (name, version="1.0") 记录定义——消费者执行时从 store 重取（KTD-A）。 */
    private void recordDefinition(String name) {
        versionManager.recordWorkflowDefinition(name, parser.parse(TWO_NODE_YAML));
    }

    /** 轮询 findStatus 到终态（SUCCESS/FAILED），上限 20s。 */
    private WorkflowStatus awaitTerminal(String workflowId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            WorkflowStatus status = checkpointManager.findStatus(workflowId).orElse(null);
            if (status == WorkflowStatus.SUCCESS || status == WorkflowStatus.FAILED) {
                return status;
            }
            Thread.sleep(200L);
        }
        throw new AssertionError("轮询工作流终态超时: " + workflowId);
    }

    private List<NodeOutputStore> completedNodes(String workflowId, int superStep) {
        return checkpointManager.findCompletedNodes(workflowId, superStep);
    }

    private static boolean kafkaReachable() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", 9092), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ──────────────────── 最小测试 context ────────────────────

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class})
    @EnableKafka
    static class KafkaE2eContext {

        /** 引擎执行计数：replay 幂等断言用（wrap MockAgentFunction 数真实执行）。 */
        @Bean
        AtomicInteger executionCounter() {
            return new AtomicInteger();
        }

        @Bean
        WorkflowDSLParser workflowDSLParser() {
            return new WorkflowDSLParser();
        }

        @Bean
        BspEngine bspEngine() {
            return new BspEngine(new DAGLayerer());
        }

        @Bean
        ChannelReducer channelReducer() {
            return new ChannelReducer();
        }

        @Bean
        CheckpointManager checkpointManager() {
            return new InMemoryCheckpointManager();
        }

        @Bean
        WorkflowVersionManager workflowVersionManager() {
            return new WorkflowVersionManager(new InMemoryWorkflowDefinitionStore());
        }

        @Bean
        NodeRegistry nodeRegistry(AtomicInteger executionCounter) {
            // 内联 mock（kafka-starter 不依赖 adapters 模块）：每次执行计数 + 返回静态输出
            return new NodeRegistry(name -> (AgentFunction) input -> {
                executionCounter.incrementAndGet();
                return AgentOutput.of("mock:" + input.nodeId());
            });
        }

        @Bean
        WorkflowExecutionService workflowExecutionService(
                BspEngine bspEngine, NodeRegistry nodeRegistry, CheckpointManager checkpointManager,
                ChannelReducer channelReducer, WorkflowVersionManager versionManager) {
            return new WorkflowExecutionService(
                    bspEngine, nodeRegistry, checkpointManager, channelReducer, versionManager);
        }
    }
}
