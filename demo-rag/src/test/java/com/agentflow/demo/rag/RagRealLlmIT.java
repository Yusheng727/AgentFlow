package com.agentflow.demo.rag;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.observability.AgentFlowMetrics;

import dev.langchain4j.model.openai.OpenAiChatModel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 真实 LLM 端到端集成测试（Failsafe {@code *IT}，KTD-6 从 mock 升级到真实检索增强端到端）。
 *
 * <p>链路：DSL → BspEngine → {@code agent: rag}（RagAgentFunction 检索知识库 → 增强 prompt）→
 * 真实 {@link LangChain4jAgentAdapter}（OpenAI 兼容 → DeepSeek）→ 真实 LLM。断言：输出非空 + trace
 * 真实 token>0（证明增强后 prompt 真被真实 LLM 消费，非 mock 空转）。
 *
 * <p>凭证 env {@code DEEPSEEK_API_KEY}；未设置 → 整类跳过（本地 verify 不红、不烧钱）。
 */
class RagRealLlmIT {

    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String MODEL = "deepseek-chat";

    @BeforeAll
    static void requireApiKey() {
        Assumptions.assumeTrue(System.getenv("DEEPSEEK_API_KEY") != null
                && !System.getenv("DEEPSEEK_API_KEY").isBlank(),
                "缺 DEEPSEEK_API_KEY 环境变量 → 跳过 RAG 真实 LLM IT（不烧钱、本地 verify 不红）");
    }

    @Test
    @DisplayName("RAG 端到端：agent: rag 检索知识库 → 增强 prompt → 真实 LLM → 输出非空 + 真实 token>0")
    void ragRealLlmEndToEnd() throws Exception {
        AgentFlowMetrics metrics = new AgentFlowMetrics(new SimpleMeterRegistry());
        BspEngine engine = new BspEngine(new DAGLayerer(), null, null, null, null, metrics);

        // 内置知识库：检索到 BSP 相关文档 → 增强 prompt → 真实 LLM 据上下文回答
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add("AgentFlow 使用 BSP 执行模型驱动多层 Agent 协作，并行执行 + barrier 同步合并 channel");
        store.add("两级 Checkpoint 提供节点级防重复计费与 barrier 级崩溃恢复");
        store.add("HITL 审批允许节点在 super-step barrier 暂停等待人工决策，批准后恢复执行");

        String key = System.getenv("DEEPSEEK_API_KEY");
        dev.langchain4j.model.chat.ChatModel chat = OpenAiChatModel.builder()
                .baseUrl(BASE_URL).apiKey(key).modelName(MODEL).build();
        com.agentflow.adapters.langchain4j.LangChain4jAgentAdapter real =
                new com.agentflow.adapters.langchain4j.LangChain4jAgentAdapter(
                        chat, java.util.List.of(), null, Function.identity(),
                        metrics, MODEL, null);
        AgentFunction rag = new RagAgentFunction(store, real);

        WorkflowDefinition def = new WorkflowDSLParser().parse(new ByteArrayInputStream("""
                agentflow:
                  version: "1.0"
                nodes:
                  - id: q
                    agent: rag
                    prompt_template: "AgentFlow 的执行模型是什么？基于检索到的上下文回答。"
                edges: []
                """.getBytes(StandardCharsets.UTF_8)));

        WorkflowContext ctx = engine.execute(def, new NodeRegistry(Map.of("rag", rag)), Map.of(),
                new com.agentflow.engine.checkpoint.InMemoryCheckpointManager(),
                new com.agentflow.engine.ChannelReducer(), "kdt6-rag-real");

        // 真实输出：检索命中 BSP 文档 → 增强 → 真实 LLM 产出的答案非空
        Object answer = ctx.getValue("q");
        assertThat(answer).isNotNull();
        // 真实 token>0：增强后 prompt 真被真实 LLM 消费（非 mock 的 0）
        assertThat(metrics.totalCost()).isGreaterThan(0.0);
    }
}
