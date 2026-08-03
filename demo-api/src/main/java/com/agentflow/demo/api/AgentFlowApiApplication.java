package com.agentflow.demo.api;

import com.agentflow.starter.AgentFlowAutoConfiguration;

import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 可运行 REST API Server（后续任务 #9）。
 *
 * <p>把 agentflow-api 的 {@code WorkflowController} / {@code TraceController} /
 * {@code DiagnosisController} + {@code ApiKeyAuthFilter} 接成可启动的 Spring Boot 应用，
 * mock 模式零成本跑通「真实 API 优先」路径——UI（Vite proxy {@code /api → localhost:8080}）
 * demo 依赖的后端可达。
 *
 * <p>扫描范围：控制器在 {@code com.agentflow.api}，本应用 + {@link ApiConfig} 在 {@code com.agentflow.demo.api}。
 *
 * <p><b>exclude（注解级权威排除，boot 与测试均生效）</b>：
 * <ul>
 *   <li>{@link AgentFlowAutoConfiguration}：starter 自动配置经 AutoConfiguration.imports 自动加载并
 *       注册同名 Bean（含裸引擎无 trace、空 NodeRegistry），与 {@link ApiConfig} 冲突——由 ApiConfig 全量接管。</li>
 *   <li>spring-ai OpenAI 六自动配置：mock 模式不调 LLM，排除可避免缺 api-key 时启动报错。</li>
 *   <li>JDBC DataSource 自动配置：mock 用 InMemoryCheckpointManager，无 DB——在 Boot 4.1 该自动配置
 *       位于 {@code spring-boot-jdbc} 模块/包 {@code org.springframework.boot.jdbc.autoconfigure}，不在本模块
 *       编译类路径，故经 application.yml 的 {@code spring.autoconfigure.exclude} 字符串排除（见 {@code application.yml}）。</li>
 * </ul>
 */
@SpringBootApplication(
        exclude = {
                AgentFlowAutoConfiguration.class,
                OpenAiChatAutoConfiguration.class,
                OpenAiEmbeddingAutoConfiguration.class,
                OpenAiImageAutoConfiguration.class,
                OpenAiAudioTranscriptionAutoConfiguration.class,
                OpenAiAudioSpeechAutoConfiguration.class,
                OpenAiModerationAutoConfiguration.class
        },
        scanBasePackages = {"com.agentflow.api", "com.agentflow.demo.api"})
public class AgentFlowApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentFlowApiApplication.class, args);
    }
}
