package com.agentflow.kafka;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.api.WorkflowExecutionService;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.checkpoint.CheckpointManager;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.version.InMemoryWorkflowDefinitionStore;
import com.agentflow.version.WorkflowVersionManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.consumer.ConsumerConfig;
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
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KTD-E 兼容冒烟（U2 计划要求但此前未交付的无 broker context 测试）。
 *
 * <p>无需真实 Kafka 也能验证 kafka-starter 自动装配自洽：context 能起（不连 broker，Kafka 消费懒连接）、
 * 自持 {@code agentflowKafkaObjectMapper} bean 存在、wire 消息经该 mapper 往返一致（含 null version /
 * 中文 inputs / java.time 值）、消费端 {@code auto.offset.reset} 默认 earliest（review 残留闭环）。
 *
 * <p>与 {@link KafkaDispatchE2eIT} 分工：E2E 证明真 broker 全链路，本测试证明无 broker 时装配与
 * serde 也不炸——KTD-E「版本兼容冒烟先行、失败即停不深入」的可回归件。
 */
@SpringBootTest(classes = KafkaCompatContextLoadTest.CompatContext.class,
        properties = {
                "agentflow.kafka.enabled=true",
                "spring.kafka.bootstrap-servers=localhost:9092",
        })
class KafkaCompatContextLoadTest {

    @Autowired
    private ObjectMapper agentflowKafkaObjectMapper;

    @Autowired
    private ConsumerFactory<String, String> agentflowKafkaConsumerFactory;

    @Test
    @DisplayName("KTD-E 冒烟：context 起 + auto.offset.reset 默认 earliest")
    void contextLoadsAndDefaultOffsetEarliest() {
        // 装配面默认值（reliability review：earliest 防新消费组订阅前丢提交）
        Map<String, Object> props =
                ((DefaultKafkaConsumerFactory<String, String>) agentflowKafkaConsumerFactory)
                        .getConfigurationProperties();
        assertThat(props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
        assertThat(agentflowKafkaObjectMapper).isNotNull();
    }

    @Test
    @DisplayName("workflow 消息经自持 mapper 往返一致（含 null version / 中文 / java.time）")
    void wireMessageRoundTripsThroughOwnedMapper() throws Exception {
        WorkflowExecutionMessage original = new WorkflowExecutionMessage(
                "wf-1", "中文工作流", null,
                Map.of("name", "张三", "created", LocalDate.of(2026, 8, 18), "at", Instant.parse("2026-08-18T12:00:00Z")));

        String json = agentflowKafkaObjectMapper.writeValueAsString(original);
        WorkflowExecutionMessage round = agentflowKafkaObjectMapper.readValue(json, WorkflowExecutionMessage.class);

        assertThat(round.workflowId()).isEqualTo("wf-1");
        assertThat(round.workflowName()).isEqualTo("中文工作流");
        assertThat(round.version()).isNull();
        assertThat(round.inputs().get("name")).isEqualTo("张三");
        assertThat(round.inputs().get("created")).isEqualTo(LocalDate.of(2026, 8, 18).toString());
        assertThat(round.inputs().get("at")).isEqualTo(Instant.parse("2026-08-18T12:00:00Z").toString());
    }

    // ──────────────────── 无 broker context（stub 引擎栈，不连真实 Kafka） ────────────────────

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class})
    @EnableKafka
    static class CompatContext {

        @Bean
        WorkflowDSLParser workflowDSLParser() {
            return new WorkflowDSLParser();
        }

        @Bean
        BspEngine bspEngine() {
            return new BspEngine();
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
        NodeRegistry nodeRegistry() {
            return new NodeRegistry(name -> (AgentFunction) input -> AgentOutput.of("mock:" + input.nodeId()));
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
