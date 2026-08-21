package com.agentflow.demo.rag;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.adapters.langchain4j.LangChain4jAgentAdapter;
import com.agentflow.adapters.mock.MockAgentFunction;
import com.agentflow.observability.AgentFlowMetrics;

import dev.langchain4j.model.openai.OpenAiChatModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;

/**
 * RAG Demo 装配（U8）：注册 {@code rag} agent → {@link RagAgentFunction}。
 *
 * <p>默认 wrapped = {@link MockAgentFunction}（零 LLM 密钥，离线可测）；真实模型经
 * {@code agentflow.rag.real.enabled=true} 且 env {@code DEEPSEEK_API_KEY} 门控接入
 * （{@link LangChain4jAgentAdapter}，OpenAI 兼容 → DeepSeek，同 {@code agentflow.real} 模式，
 * 凭证只从 env，禁止写死）。
 */
@Configuration
public class RagDemoConfig {

    private static final Logger log = LoggerFactory.getLogger(RagDemoConfig.class);

    /** 内置知识库：2 篇业务文档（确定性词袋，离线检索）。 */
    @Bean
    public InMemoryVectorStore ragVectorStore() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add("AgentFlow 支持串行与并行 DAG 编排，BSP 执行模型驱动");
        store.add("HITL 审批：节点可暂停等待人工决策，批准后恢复执行");
        store.add("checkpoint 支持崩溃恢复与审批恢复两类路径");
        return store;
    }

    /**
     * {@code agent: rag} 节点。默认 mock wrapped（零密钥、离线可测）；真实模型：
     * {@code agentflow.rag.real.enabled=true} + env {@code DEEPSEEK_API_KEY} → 用
     * {@link LangChain4jAgentAdapter} 委托（检索增强后走真实 LLM，KTD-6 从 mock 升级端到端）。
     */
    @Bean
    public AgentFunction ragAgent(InMemoryVectorStore vectorStore,
                                  AgentFlowMetrics metrics,
                                  @Value("${agentflow.rag.model:gpt-4o-mini}") String mockModel,
                                  @Value("${agentflow.rag.real.enabled:false}") boolean realEnabled,
                                  @Value("${agentflow.rag.real.model:deepseek-chat}") String realModel,
                                  @Value("${agentflow.rag.real.base-url:https://api.deepseek.com}") String realBaseUrl) {
        AgentFunction delegate = realEnabled
                ? realDelegate(metrics, realModel, realBaseUrl)
                : new MockAgentFunction(metrics, mockModel, null);
        return new RagAgentFunction(vectorStore, delegate);
    }

    /** 为 BspEngine 提供 {@code rag} agent 解析（其它 agent 名 fallback mock）。 */
    @Bean
    public NodeRegistry ragNodeRegistry(AgentFunction ragAgent,
                                        @Value("${agentflow.rag.model:gpt-4o-mini}") String model) {
        NodeRegistry registry = new NodeRegistry(name -> new MockAgentFunction(null, model, null));
        registry.register("rag", ragAgent);
        return registry;
    }

    /** 真实 LLM 委托（OpenAI 兼容 → DeepSeek）。凭证从 env {@code DEEPSEEK_API_KEY} 读，缺则启动失败（不做静默回落）。 */
    private static LangChain4jAgentAdapter realDelegate(AgentFlowMetrics metrics, String model, String baseUrl) {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "agentflow.rag.real.enabled=true 但缺 DEEPSEEK_API_KEY 环境变量：真实 LLM 委托需要凭证，禁止硬编码。"
                            + " 请 export DEEPSEEK_API_KEY=... 后重启，或设 agentflow.rag.real.enabled=false 退回 mock。");
        }
        OpenAiChatModel chat = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .build();
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(
                chat, java.util.List.of(), null, Function.identity(),
                metrics, model, null);
        log.info("RAG 真实 LLM 委托已启用：model={} baseUrl={}（凭证来自 env DEEPSEEK_API_KEY）", model, baseUrl);
        return adapter;
    }
}
