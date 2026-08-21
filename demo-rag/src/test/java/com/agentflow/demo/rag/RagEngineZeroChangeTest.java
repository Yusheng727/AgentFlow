package com.agentflow.demo.rag;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.agent.AgentOutput;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U8 核心验证（KTD-6「引擎层零改动、Agent 扩展点成立」）：{@code BspEngine} 直接跑含
 * {@code agent: rag} 节点的工作流，无需任何引擎/DSL 改动即成功输出。
 *
 * <p>Rag 节点接入方式：NodeRegistry 注册 {@code rag} → {@link RagAgentFunction}（wrapped 委托捕获
 * 增强后 prompt，断言拖取真实发生）。引擎只看到普通 AgentFunction 契约，看不到 RAG 内部。
 */
class RagEngineZeroChangeTest {

    @Test
    @DisplayName("BspEngine 零改动跑通 agent: rag 节点（KTD-6 扩展点证明）")
    void engineRunsRagNodeWithoutChange() throws Exception {
        // 业务知识库（词间空格，tokenSet 零依赖分词可计算重叠）
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add("串行 DAG 编排 引擎 BSP 驱动");
        store.add("HITL 审批 暂停 人工 决策");

        // wrapped 委托：捕获增强后 prompt，返回静态输出
        java.util.concurrent.atomic.AtomicReference<String> received = new java.util.concurrent.atomic.AtomicReference<>();
        AgentFunction delegate = in -> {
            received.set(in.promptTemplate());
            return AgentOutput.of("rag-answer");
        };
        RagAgentFunction rag = new RagAgentFunction(store, delegate);

        // YAML：单个 rag 节点（引擎层面它就是个普通 AgentFunction 契约）
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: q, agent: rag, prompt_template: "串行 编排 检索", mock_response: "x" }
                edges: []
                """;
        WorkflowDefinition def = new WorkflowDSLParser().parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        WorkflowContext result = new BspEngine().execute(
                def, new NodeRegistry(Map.of("rag", rag)), Map.of(),
                new InMemoryCheckpointManager(), new ChannelReducer(), "kdt6-rag");

        // 引擎成功输出（channel = 节点 id 写入 content）
        assertThat(result.getValue("q")).isEqualTo("rag-answer");
        // 增强 prompt 含命中文档（拖取真实发生，非空转）
        assertThat(received.get()).contains("串行 DAG 编排 引擎 BSP 驱动").contains("串行 编排 检索");
    }

    @Test
    @DisplayName("mock 委托回退：未显式注册 rag 却用 fallback mock 也可零密钥跑通（回归）")
    void mockFallbackRunsZeroKey() throws Exception {
        // fallback mock：任意 agent 名 → MockAgentFunction 返回 mock_response（无需 LLM key）
        NodeRegistry registry = new NodeRegistry(name -> new com.agentflow.adapters.mock.MockAgentFunction(null, "gpt-4o-mini", null));
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: q, agent: mock, prompt_template: "x", mock_response: "ok" }
                edges: []
                """;
        WorkflowDefinition def = new WorkflowDSLParser().parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        WorkflowContext result = new BspEngine().execute(
                def, registry, Map.of(),
                new InMemoryCheckpointManager(), new ChannelReducer(), "mock-rag");

        assertThat(result.getValue("q")).isEqualTo("ok");
    }
}
