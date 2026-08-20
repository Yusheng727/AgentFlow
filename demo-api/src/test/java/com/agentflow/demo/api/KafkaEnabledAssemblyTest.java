package com.agentflow.demo.api;

import com.agentflow.api.WorkflowController;
import com.agentflow.api.WorkflowDispatcher;
import com.agentflow.kafka.KafkaWorkflowDispatcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * demo-api 在 {@code agentflow.kafka.enabled=true} 下的真实装配 seam（review #7）。
 *
 * <p>此前 kafka-starter 的 dispatcher/consumer 只在 {@code KafkaDispatchE2eIT}（手写最小 context，无
 * {@code ApiConfig} / {@code WorkflowController}）里被测试；demo-api 全应用 {@code enabled=true} 时
 * {@code ApiConfig.workflowDispatcher}（@ConditionalOnProperty matchIfMissing）让位、容器选择 kafka-starter
 * 的 {@link KafkaWorkflowDispatcher}、{@link WorkflowController} 注入它——这条 seam 只靠 ROADMAP live
 * 命令人工验证。本测试起 {@link AgentFlowApiApplication} 完整 context（含全部 exclude + ApiConfig），
 * 断言注入的 {@link WorkflowDispatcher} 是 {@link KafkaWorkflowDispatcher}，把 seam 纳入自动化。
 *
 * <p>装配冒烟只用 wiring、不连 broker：{@code spring.kafka.listener.auto-startup=false}（@KafkaListener
 * 容器照建不 poll）+ {@code spring.kafka.admin.auto-create=false}（Boot KafkaAdmin 不做启动期 topic 创建，
 * 避免无 broker 连接日志噪声——开发记录已如实标注该启动行为面）。真传输闭环由 {@code KafkaDispatchE2eIT}
 * （kafka-starter，Kafka 门控）覆盖；本测试<b>不门控</b>，无 broker 也跑，专抓装配型错误。
 */
@SpringBootTest(
        classes = AgentFlowApiApplication.class,
        properties = {
                "agentflow.kafka.enabled=true",
                "spring.kafka.bootstrap-servers=localhost:9092",
                "spring.kafka.consumer.group-id=kafka-demo-assembly",
                "spring.kafka.listener.auto-startup=false",
                "spring.kafka.admin.auto-create=false"
        })
class KafkaEnabledAssemblyTest {

    @Autowired
    private WorkflowDispatcher dispatcher;
    @Autowired
    private WorkflowController controller;

    @Test
    @DisplayName("enabled=true 全应用装配：dispatcher 换成 Kafka、controller 注入 Kafka 派发")
    void kafkaDispatcherSwapsInAndControllerWiresIt() {
        assertThat(dispatcher).isInstanceOf(KafkaWorkflowDispatcher.class);
        assertThat(controller).isNotNull();
    }
}