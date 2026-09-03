package com.agentflow.demo.supplier;

import com.agentflow.agent.AgentFunction;
import com.agentflow.adapters.mock.MockAgentFunction;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;

import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.Map;

/**
 * 供应商风险评估主 Demo（U10，R15）。
 *
 * <p>工作流：3 个专家 Agent 并行分析（财务 / 合规 / 声誉）→ Supervisor 汇总评级。
 * BSP 分层：super-step 0 三节点并行 → super-step 1 汇总节点 SpEL 引用三路 channel。
 *
 * <h3>运行模式</h3>
 * <ul>
 *   <li><b>mock 模式</b>（默认，{@code agentflow.mock.enabled=true}）：用 {@link MockAgentFunction}
 *       从 YAML {@code mock_response} 读预设响应，零 LLM 成本，验证拓扑 + SpEL 引用 + BSP 并行</li>
 *   <li><b>真实模式</b>（{@code agentflow.mock.enabled=false} + 配 OpenAI key）：4 个 AgentFunction Bean
 *       （{@link com.agentflow.demo.supplier.agents.FinancialAnalysisAgent} 等）走 Spring AI ChatClient</li>
 * </ul>
 *
 * <p>mock 模式排除 spring-ai OpenAI 六自动配置（对齐 demo-api 的注解级权威排除）：classpath 引入
 * spring-ai-starter-model-openai 但无 API key，不排除会在启动期报
 * "At least one credential source must be specified"——README 快速开始缺 key 跑不通的根因。
 *
 * <p>U10 范围（v4.3 解耦）：编程式组装引擎跑通 mock 模式，验证引擎核心能力。
 * {@code @EnableAgentFlow} 一键启动 + REST 端点在 U13 Starter 封装落地。
 */
@SpringBootApplication(exclude = {
        OpenAiChatAutoConfiguration.class,
        OpenAiEmbeddingAutoConfiguration.class,
        OpenAiImageAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class,
        OpenAiAudioSpeechAutoConfiguration.class,
        OpenAiModerationAutoConfiguration.class})
public class SupplierRiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupplierRiskApplication.class, args);
    }

    /**
     * 启动入口 Bean：解析 supplier-risk.yml 并跑通工作流（mock 模式下零 LLM 成本）。
     */
    @Bean
    public String runSupplierRiskWorkflow() {
        MockAgentFunction mock = new MockAgentFunction();
        Map<String, AgentFunction> agents = Map.of(
                "finance-agent", mock,
                "compliance-agent", mock,
                "reputation-agent", mock,
                "aggregate-agent", mock);

        try (InputStream yaml = new ClassPathResource("workflows/supplier-risk.yml").getInputStream()) {
            WorkflowDefinition def = new WorkflowDSLParser().parse(yaml);
            WorkflowContext result = new BspEngine().execute(
                    def, agents, Map.of("supplier", "Acme Corp"),
                    new InMemoryCheckpointManager(), new ChannelReducer(), "demo-supplier-risk-1");
            System.out.println("=== 供应商风险评估结果 ===");
            System.out.println(result.getValue("aggregate-rating"));
            return (String) result.getValue("aggregate-rating");
        } catch (Exception e) {
            throw new RuntimeException("供应商风险评估工作流执行失败", e);
        }
    }
}
