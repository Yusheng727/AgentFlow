package com.agentflow.kafka;

import com.agentflow.api.WorkflowExecutionService;
import com.agentflow.engine.checkpoint.CheckpointManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 异步分发自动装配（KTD-C/F/D，v1.1）。
 *
 * <p>属性门控：{@code agentflow.kafka.enabled=true} 才装配（对齐 {@code agentflow.real.enabled}，
 * 避免 starter 进 classpath 即静默替换执行路径）。装配后提供 {@link KafkaWorkflowDispatcher}
 * 覆盖默认本地 dispatcher，submit/retry 走 Kafka。
 *
 * <p>wire 格式 = JSON 字符串（KTD-D）：StringSerializer + 项目 ObjectMapper 手动序列化
 * {@link WorkflowExecutionMessage}——避开 spring-kafka 4.x 废弃的 JsonSerializer/JsonDeserializer，
 * 且序列化细节完全可控。bean 名加 {@code agentflow} 前缀，避免与 spring-boot 的
 * KafkaAutoConfiguration 默认 bean 冲突。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "agentflow.kafka", name = "enabled", havingValue = "true")
@ConditionalOnBean(WorkflowExecutionService.class)
@EnableKafka
public class KafkaAgentFlowAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "agentflowKafkaProducerFactory")
    public ProducerFactory<String, String> agentflowKafkaProducerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    @ConditionalOnMissingBean(name = "agentflowKafkaTemplate")
    public KafkaTemplate<String, String> agentflowKafkaTemplate(
            ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    @ConditionalOnMissingBean(name = "agentflowKafkaConsumerFactory")
    public ConsumerFactory<String, String> agentflowKafkaConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id:agentflow-workers}") String groupId,
            @Value("${spring.kafka.consumer.auto-offset-reset:earliest}") String autoOffsetReset) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    @ConditionalOnMissingBean(name = "agentflowKafkaListenerContainerFactory")
    public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, String>>
            agentflowKafkaListenerContainerFactory(
                    ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }

    @Bean
    public KafkaWorkflowDispatcher kafkaWorkflowDispatcher(
            KafkaTemplate<String, String> agentflowKafkaTemplate, ObjectMapper agentflowKafkaObjectMapper) {
        return new KafkaWorkflowDispatcher(agentflowKafkaTemplate, agentflowKafkaObjectMapper);
    }

    @Bean
    public KafkaWorkflowConsumer kafkaWorkflowConsumer(
            WorkflowExecutionService workflowExecutionService, CheckpointManager checkpointManager,
            ObjectMapper agentflowKafkaObjectMapper) {
        return new KafkaWorkflowConsumer(workflowExecutionService, checkpointManager, agentflowKafkaObjectMapper);
    }

    /**
     * wire 格式专用 Jackson 2 ObjectMapper（KTD-D）：String 承载 JSON 的序列化/反序列化归属 starter 自持，
     * 不依赖应用容器提供——Boot 4.1 容器的 ObjectMapper 是 Jackson 3（{@code tools.jackson}），与
     * kafka-starter 的 {@code com.fasterxml.jackson} 类型不同，注入外部 bean 会因类型缺失启动失败。
     *
     * <p>消费端 {@code auto.offset.reset} 默认 {@code earliest}（reliability review P1）：新消费组无已提交
     * offset 时，{@code latest} 会从分区末端起读、静默丢掉「订阅前已 produce」的提交（工作流永 PENDING、
     * 无重调和兜底）。本消费者幂等（终态跳过，KTD-F），earliest 只是把旧消息重扫一遍并跳过，无双计费——
     * 对任务队列语义 strictly safer。
     */
    @Bean
    @ConditionalOnMissingBean(name = "agentflowKafkaObjectMapper")
    public ObjectMapper agentflowKafkaObjectMapper() {
        return new ObjectMapper();
    }
}
