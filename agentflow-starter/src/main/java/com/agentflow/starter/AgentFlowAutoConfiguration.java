package com.agentflow.starter;

import com.agentflow.agent.AgentFunction;
import com.agentflow.adapters.mock.MockAgentFunction;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;

/**
 * AgentFlow 自动配置（U9 起引入，U13 扩展完整 Bean 注册）。
 *
 * <p>v1（U9）只落地 Mock LLM 模式的核心 Bean：
 * {@code agentflow.mock.enabled=true} 时，提供一个把所有 agent name 解析为
 * {@link MockAgentFunction} 单例的 resolver Bean——下游（BspEngine / WorkflowController）
 * 注入此 resolver 即可在不发任何 LLM 请求的情况下跑通工作流。
 *
 * <p>MockAgentFunction 从 {@link com.agentflow.agent.AgentInput#mockResponse()} 读预设响应，
 * 故与 agent name 无关，单例即可服务所有节点。
 *
 * <p><b>范围限制（U13 补齐）</b>：本配置只注册 mockAgentResolver，不注册 BspEngine /
 * WorkflowDSLParser / NodeRegistry / CheckpointManager / WorkflowController——完整端到端
 * Bean 装配在 U13 Starter 封装时落地。mock 模式下端到端跑通需要 U13 的完整 AutoConfiguration
 * 或调用方手动注入 resolver + 引擎。此处不预注册 BspEngine 是因为 BspEngine 无参构造不持有
 * resolver（resolver 按 execute() 调用传入），孤立注册无意义——避免误导调用方「Bean 存在即可用」。
 */
@Configuration
@EnableConfigurationProperties(AgentFlowProperties.class)
public class AgentFlowAutoConfiguration {

    /**
     * Mock 模式下的 AgentFunction resolver：任意 agent name → MockAgentFunction 单例。
     * <p>BspEngine / WorkflowController 注入此 Bean 后，mock 模式下所有节点走 MockAgentFunction。
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentflow.mock", name = "enabled", havingValue = "true")
    public Function<String, AgentFunction> mockAgentResolver() {
        MockAgentFunction mock = new MockAgentFunction();
        return name -> mock;
    }
}
