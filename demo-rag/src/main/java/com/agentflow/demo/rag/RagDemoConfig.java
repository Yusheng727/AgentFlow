package com.agentflow.demo.rag;

import com.agentflow.agent.AgentFunction;
import com.agentflow.adapters.mock.MockAgentFunction;
import com.agentflow.agent.NodeRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG Demo 装配（U8）：注册 {@code rag} agent → {@link RagAgentFunction}。
 *
 * <p>默认 wrapped = {@link MockAgentFunction}（零 LLM 密钥，离线可测）；可选真实模型接入走
 * {@code agentflow.rag.real.*}（同 {@code agentflow.real} 模式，凭证只从 env）。
 */
@Configuration
public class RagDemoConfig {

    /** 内置知识库：2 篇业务文档（确定性词袋，离线检索）。 */
    @Bean
    public InMemoryVectorStore ragVectorStore() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add("AgentFlow 支持串行与并行 DAG 编排，BSP 执行模型驱动");
        store.add("HITL 审批：节点可暂停等待人工决策，批准后恢复执行");
        store.add("checkpoint 支持崩溃恢复与审批恢复两类路径");
        return store;
    }

    /** {@code agent: rag} 节点：默认 mock wrapped（零密钥）；真实 LLM 接入按 {@code agentflow.rag.real.*} 模式 Deferred。 */
    @Bean
    public AgentFunction ragAgent(InMemoryVectorStore vectorStore,
                                  @Value("${agentflow.rag.model:gpt-4o-mini}") String model) {
        return new RagAgentFunction(vectorStore, new MockAgentFunction(null, model, null));
    }

    /** 为 BspEngine 提供 {@code rag} agent 解析（其它 agent 名 fallback mock）。 */
    @Bean
    public NodeRegistry ragNodeRegistry(AgentFunction ragAgent,
                                        @Value("${agentflow.rag.model:gpt-4o-mini}") String model) {
        NodeRegistry registry = new NodeRegistry(name -> new MockAgentFunction(null, model, null));
        registry.register("rag", ragAgent);
        return registry;
    }
}
