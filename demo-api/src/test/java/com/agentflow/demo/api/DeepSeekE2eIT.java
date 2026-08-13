package com.agentflow.demo.api;

import com.agentflow.adapters.langchain4j.LangChain4jAgentAdapter;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.observability.AgentFlowMetrics;
import com.agentflow.observability.ExecutionTraceRegistry;
import com.agentflow.prompt.OutputSchemaValidator;

import dev.langchain4j.model.openai.OpenAiChatModel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeepSeek 真实 LLM 端到端集成测试（档 1 生产接线 Failsafe {@code *IT}）。
 *
 * <p>把 DSL → BspEngine → 真实 {@link LangChain4jAgentAdapter}（OpenAI 兼容 → DeepSeek）→ 真实 LLM 的完整链路
 * 跑通：2 节点串行（n1 出 JSON 一句话 → n2 引 ${context.n1} 翻译成英文），断言 n2 有<b>真实非空内容</b>、
 * trace 记录了 <b>真实 token&gt;0</b>（非 mock 的 0）——证明 C1 记账 / KTD-7 第二适配器在真实部署路径生效。
 *
 * <p>凭证从 env {@code DEEPSEEK_API_KEY} 读；<b>未设置该 env 时整类跳过</b>（本地 {@code mvn verify} 不红、不烧钱）。
 * CI / 真实部署注入 key 后执行。无任何 key 硬编码。
 */
class DeepSeekE2eIT {

    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String MODEL = "deepseek-chat";

    @BeforeAll
    static void requireApiKey() {
        Assumptions.assumeTrue(System.getenv("DEEPSEEK_API_KEY") != null
                && !System.getenv("DEEPSEEK_API_KEY").isBlank(),
                "缺 DEEPSEEK_API_KEY 环境变量 → 跳过真实 LLM 端到端 IT（不烧钱、本地 verify 不红）");
    }

    @Test
    @DisplayName("端到端：DSL → BspEngine → DeepSeek 真实适配器，2 节点串行逐级传参，content 非空 + trace 真实 token>0")
    void deepSeekWorkflowRoundTripsRealContent() throws Exception {
        ExecutionTraceRegistry traceRegistry = new ExecutionTraceRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(new SimpleMeterRegistry());
        BspEngine engine = new BspEngine(new DAGLayerer(), null, null, null, traceRegistry, metrics);

        String key = System.getenv("DEEPSEEK_API_KEY");
        OpenAiChatModel chat = OpenAiChatModel.builder()
                .baseUrl(BASE_URL).apiKey(key).modelName(MODEL).build();
        LangChain4jAgentAdapter real = new LangChain4jAgentAdapter(
                chat, List.of(), null, Function.identity(),
                metrics, MODEL, new OutputSchemaValidator());
        NodeRegistry registry = new NodeRegistry();
        registry.register("deepseek", real);

        WorkflowDefinition def = new WorkflowDSLParser().parse("""
                nodes:
                  - id: n1
                    agent: deepseek
                    prompt_template: "用一句话回答：JSON 是什么？"
                  - id: n2
                    agent: deepseek
                    prompt_template: "请把【${context.n1}】翻译成英文，只输出译文，不要任何解释。"
                edges:
                  - from: n1
                    to: n2
                """);

        WorkflowContext ctx = engine.execute(def, registry, Map.of());

        // 真实内容：n1/n2 都有非空文本，n2 是据 n1 生成（逐级传参生效）
        String n1 = (String) ctx.getValue("n1");
        String n2 = (String) ctx.getValue("n2");
        assertThat(n1).isNotBlank();
        assertThat(n2).isNotBlank();
        assertThat(n2).isNotEqualTo(n1);

        // 真实 token：LangChain4j 适配器经 recordTokens 记账 → 成本>0 证明真实计费（非 mock 的 0）
        assertThat(metrics.totalCost()).isGreaterThan(0.0);
    }
}
