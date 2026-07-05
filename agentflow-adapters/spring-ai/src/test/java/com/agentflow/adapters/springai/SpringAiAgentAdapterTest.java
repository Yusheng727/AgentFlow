package com.agentflow.adapters.springai;

import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.FatalException;
import com.agentflow.agent.TransientException;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.observability.ExecutionTrace;
import com.agentflow.observability.NodeTrace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SpringAiAgentAdapter 骨架测试（U3-3）：stub ChatModel + SpEL 解析 + SpEL 安全 +
 * 异常 Transient/Fatal 映射 + NodeTrace 写入。
 */
class SpringAiAgentAdapterTest {

    /** 可配置 stub ChatModel：返回固定 content + DefaultUsage，或抛指定异常；捕获收到的 prompt。
     *  设 caller 后按 prompt 文本动态返回 content（schema 重试测试用）。 */
    static class StubChatModel implements ChatModel {
        String content = "stub-ok";
        int promptTokens = 10;
        int completionTokens = 20;
        RuntimeException throwOnCall;
        java.util.function.Function<String, String> caller;
        volatile String capturedPrompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            capturedPrompt = prompt.getInstructions().isEmpty() ? ""
                    : prompt.getInstructions().get(0).getText();
            if (throwOnCall != null) {
                throw throwOnCall;
            }
            String text = caller != null ? caller.apply(capturedPrompt) : content;
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(text))),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(promptTokens, completionTokens)).build()
            );
        }
    }

    private static AgentInput input(String nodeId, String template, Map<String, Object> channels,
                                   Map<String, Object> inputs) {
        WorkflowContext ctx = new WorkflowContext(channels);
        return new AgentInput(nodeId, "test-agent", template, ctx.readOnlySnapshot(),
                inputs, List.of(), Map.of());
    }

    /**
     * Spring AI 2.0 实测：{@code ChatClient.create(model).prompt(x).call()} 在零 advisor 时抛
     * "No CallAdvisors available to execute"。适配器因此每次 call 注入 ≥1 个 Advisor
     * （此处用内置 SimpleLoggerAdvisor 作 pass-through；生产中 U3-4 的 TokenCounting +
     * LoggingAdvisor 即担此角色），与 KTD-7 冒烟测试（.advisors(advisor).call()）同路径。
     */
    private static ChatClient client(StubChatModel model) {
        return ChatClient.create(model);
    }

    private static List<org.springframework.ai.chat.client.advisor.api.Advisor> passThroughAdvisors() {
        return List.of(new NoOpBaseAdvisor());
    }

    /** 最小 pass-through BaseAdvisor（Spring AI 2.0 的链是 BaseAdvisor-centric，需 BaseAdvisor 触发模型调用）。 */
    static class NoOpBaseAdvisor implements org.springframework.ai.chat.client.advisor.api.BaseAdvisor {
        @Override
        public String getName() {
            return "NoOpBaseAdvisor";
        }

        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        public org.springframework.ai.chat.client.ChatClientRequest before(
                org.springframework.ai.chat.client.ChatClientRequest request,
                org.springframework.ai.chat.client.advisor.api.AdvisorChain chain) {
            return request;
        }

        @Override
        public org.springframework.ai.chat.client.ChatClientResponse after(
                org.springframework.ai.chat.client.ChatClientResponse response,
                org.springframework.ai.chat.client.advisor.api.AdvisorChain chain) {
            return response;
        }
    }

    private static SpringAiAgentAdapter adapter(StubChatModel model) {
        return new SpringAiAgentAdapter(client(model), passThroughAdvisors(), List.of(), null, Function.identity(), null);
    }

    private static SpringAiAgentAdapter adapter(StubChatModel model, ExecutionTrace trace) {
        return new SpringAiAgentAdapter(client(model), passThroughAdvisors(), List.of(), trace, Function.identity(), null);
    }

    private static SpringAiAgentAdapter adapter(StubChatModel model, ExecutionTrace trace,
                                                Function<String, String> redactor) {
        return new SpringAiAgentAdapter(client(model), passThroughAdvisors(), List.of(), trace, redactor, null);
    }

    @Test
    @DisplayName("execute：返回 content + metadata{tokens}，stub 收到原样 prompt")
    void executeBasic() throws Exception {
        StubChatModel model = new StubChatModel();
        SpringAiAgentAdapter adapter = adapter(model);

        AgentOutput out = adapter.execute(input("A", "hello", Map.of(), Map.of()));

        assertThat(out.content()).isEqualTo("stub-ok");
        assertThat(out.metadata()).containsEntry("promptTokens", 10L)
                .containsEntry("completionTokens", 20L)
                .containsEntry("totalTokens", 30L);
        assertThat(model.capturedPrompt).isEqualTo("hello");
        assertThat(out.channelWrites()).isEmpty();
        assertThat(out.structuredOutput()).isEmpty();
    }

    @Test
    @DisplayName("SpEL 解析：${context.financeAnalysis.riskScore} 从嵌套 map 取值，注入 prompt")
    void spelResolutionFromNestedChannel() throws Exception {
        StubChatModel model = new StubChatModel();
        SpringAiAgentAdapter adapter = adapter(model);

        Map<String, Object> finance = new HashMap<>();
        finance.put("riskScore", 88);
        finance.put("riskLevel", "HIGH");
        AgentOutput out = adapter.execute(input("B",
                "风险评分=${context.financeAnalysis.riskScore}, 等级=${context.financeAnalysis.riskLevel}",
                Map.of("financeAnalysis", finance), Map.of()));

        assertThat(out.content()).isEqualTo("stub-ok");
        assertThat(model.capturedPrompt).isEqualTo("风险评分=88, 等级=HIGH");
    }

    @Test
    @DisplayName("SpEL 解析：${inputs.supplier} 读工作流入参")
    void spelResolutionFromInputs() throws Exception {
        StubChatModel model = new StubChatModel();
        SpringAiAgentAdapter adapter = adapter(model);

        AgentOutput out = adapter.execute(input("C", "供应商=${inputs.supplier}",
                Map.of(), Map.of("supplier", "Acme Corp")));
        assertThat(model.capturedPrompt).isEqualTo("供应商=Acme Corp");
        assertThat(out.content()).isEqualTo("stub-ok");
    }

    @Test
    @DisplayName("SpEL 安全：${T(java.lang.System).exit(0)} 被禁，抛 FatalException")
    void spelSecurityDisallowsTypeReference() throws Exception {
        StubChatModel model = new StubChatModel();
        SpringAiAgentAdapter adapter = adapter(model);

        assertThatThrownBy(() -> adapter.execute(input("D",
                "${T(java.lang.System).exit(0)}", Map.of(), Map.of())))
                .isInstanceOf(FatalException.class)
                .hasMessageContaining("SpEL");
        // 安全违例不应触达 LLM
        assertThat(model.capturedPrompt).isNull();
    }

    @Test
    @DisplayName("null 值占位符替换为空串，不出现字面 'null'（缺失 key 仍抛 Fatal，属配置错误）")
    void spelNullPlaceholderReplacedWithEmpty() throws Exception {
        StubChatModel model = new StubChatModel();
        SpringAiAgentAdapter adapter = adapter(model);

        // channel "nullable" 存在但值为 null → 求值为 null → 替换为空串
        Map<String, Object> channels = new HashMap<>();
        channels.put("nullable", null);
        adapter.execute(input("E", "值=[${context.nullable}]", channels, Map.of()));
        assertThat(model.capturedPrompt).isEqualTo("值=[]");
    }

    @Test
    @DisplayName("异常映射：IOException cause → TransientException（可重试）")
    void mapsNetworkErrorToTransient() throws Exception {
        StubChatModel model = new StubChatModel();
        model.throwOnCall = new RuntimeException(new java.net.SocketTimeoutException("timeout"));
        SpringAiAgentAdapter adapter = adapter(model);

        assertThatThrownBy(() -> adapter.execute(input("F", "hi", Map.of(), Map.of())))
                .isInstanceOf(TransientException.class)
                .hasMessageContaining("Transient");
    }

    @Test
    @DisplayName("异常映射：普通 RuntimeException → FatalException（保守不重试）")
    void mapsGenericErrorToFatal() throws Exception {
        StubChatModel model = new StubChatModel();
        model.throwOnCall = new RuntimeException("model 400 bad request");
        SpringAiAgentAdapter adapter = adapter(model);

        assertThatThrownBy(() -> adapter.execute(input("G", "hi", Map.of(), Map.of())))
                .isInstanceOf(FatalException.class)
                .hasMessageContaining("LLM 调用失败");
    }

    @Test
    @DisplayName("NodeTrace 写入 ExecutionTrace：成功节点带 token + 摘要")
    void nodeTracePopulatedOnSuccess() throws Exception {
        StubChatModel model = new StubChatModel();
        model.content = "分析完成";
        ExecutionTrace trace = new ExecutionTrace("wf-1");
        SpringAiAgentAdapter adapter = adapter(model, trace);

        adapter.execute(input("H", "hi", Map.of(), Map.of()));

        assertThat(trace.nodes()).hasSize(1);
        NodeTrace node = trace.nodes().get(0);
        assertThat(node.nodeId()).isEqualTo("H");
        assertThat(node.status()).isEqualTo(NodeTrace.Status.SUCCESS);
        assertThat(node.totalTokens()).isEqualTo(30);
        assertThat(node.outputSummary()).isEqualTo("分析完成");
    }

    @Test
    @DisplayName("NodeTrace 写入 ExecutionTrace：失败节点带 error")
    void nodeTracePopulatedOnFailure() throws Exception {
        StubChatModel model = new StubChatModel();
        model.throwOnCall = new RuntimeException("boom");
        ExecutionTrace trace = new ExecutionTrace("wf-2");
        SpringAiAgentAdapter adapter = adapter(model, trace);

        assertThatThrownBy(() -> adapter.execute(input("I", "hi", Map.of(), Map.of())))
                .isInstanceOf(FatalException.class);
        NodeTrace node = trace.nodes().get(0);
        assertThat(node.status()).isEqualTo(NodeTrace.Status.FAILED);
        assertThat(node.error()).isEqualTo("boom");
    }

    @Test
    @DisplayName("redactor 仅脱敏 trace 摘要，不影响 AgentOutput.content（下游需真实输出）")
    void redactorAppliesToTraceOnly() throws Exception {
        StubChatModel model = new StubChatModel();
        model.content = "secret sk-12345";
        ExecutionTrace trace = new ExecutionTrace("wf-3");
        // 脱敏：把 sk-XXX 替换为 sk-***
        Function<String, String> redactor = s -> s == null ? null : s.replaceAll("sk-\\w+", "sk-***");
        SpringAiAgentAdapter adapter = adapter(model, trace, redactor);

        AgentOutput out = adapter.execute(input("J", "hi", Map.of(), Map.of()));
        // content 不脱敏（下游需要真实值）
        assertThat(out.content()).isEqualTo("secret sk-12345");
        // trace 摘要脱敏
        assertThat(trace.nodes().get(0).outputSummary()).isEqualTo("secret sk-***");
    }

    @Test
    @DisplayName("集成：TokenCountingAdvisor + LoggingAdvisor 经适配器 per-call 注入链路跑通")
    void realAdvisorsIntegrated() throws Exception {
        StubChatModel model = new StubChatModel();
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        java.util.List<String> logs = new java.util.ArrayList<>();
        List<org.springframework.ai.chat.client.advisor.api.Advisor> advisors = List.of(
                new TokenCountingAdvisor(registry, "test-agent"),
                new LoggingAdvisor(Function.identity(), logs::add));
        ExecutionTrace trace = new ExecutionTrace("wf-int");
        SpringAiAgentAdapter adapter = new SpringAiAgentAdapter(
                ChatClient.create(model), advisors, List.of(), trace, Function.identity(), null);

        AgentOutput out = adapter.execute(input("K", "hi", Map.of(), Map.of()));

        // 适配器主路径正常
        assertThat(out.content()).isEqualTo("stub-ok");
        assertThat(out.metadata()).containsEntry("totalTokens", 30L);
        // LoggingAdvisor 输出了 start + end 两行（证明 before/after 在链中跑了）
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0)).contains("call.start");
        assertThat(logs.get(1)).contains("call.end");
        // TokenCountingAdvisor 记了指标
        assertThat(registry.counter(TokenCountingAdvisor.COUNTER_NAME,
                "agent", "test-agent", "model", "unknown").count()).isEqualTo(30.0);
        // NodeTrace 由适配器写入 trace
        assertThat(trace.nodes()).hasSize(1);
        assertThat(trace.nodes().get(0).totalTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("schema 校验：节点声明 output_schema，stub 返回合法 JSON → structuredOutput 填充")
    void schemaValidationSuccess() throws Exception {
        StubChatModel model = new StubChatModel();
        model.content = "```json\n{\"riskLevel\": \"HIGH\", \"debtRatio\": 0.7}\n```";
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("riskLevel", Map.of("type", "string",
                "enum", List.of("LOW", "MEDIUM", "HIGH"))));
        schema.put("required", List.of("riskLevel"));
        AgentInput in = new AgentInput("L", "test-agent", "分析风险", new WorkflowContext(),
                Map.of(), List.of(), schema);
        SpringAiAgentAdapter adapter = new SpringAiAgentAdapter(
                client(model), passThroughAdvisors(), List.of(), null, Function.identity(),
                new OutputSchemaValidator());

        AgentOutput out = adapter.execute(in);

        assertThat(out.structuredOutput()).containsEntry("riskLevel", "HIGH");
        assertThat(out.structuredOutput()).containsEntry("debtRatio", 0.7);
        assertThat(out.content()).contains("HIGH");
    }

    @Test
    @DisplayName("schema 校验：stub 首次返回无 JSON，重试返回合法 → structuredOutput 填充（2 次调用）")
    void schemaValidationRetrySuccess() throws Exception {
        StubChatModel model = new StubChatModel();
        AtomicInteger calls = new AtomicInteger();
        model.caller = prompt -> {
            int n = calls.incrementAndGet();
            return n == 1 ? "无法生成" : "{\"riskLevel\": \"LOW\"}";
        };
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("riskLevel", Map.of("type", "string",
                "enum", List.of("LOW", "MEDIUM", "HIGH"))));
        schema.put("required", List.of("riskLevel"));
        AgentInput in = new AgentInput("M", "test-agent", "分析风险", new WorkflowContext(),
                Map.of(), List.of(), schema);
        SpringAiAgentAdapter adapter = new SpringAiAgentAdapter(
                client(model), passThroughAdvisors(), List.of(), null, Function.identity(),
                new OutputSchemaValidator());

        AgentOutput out = adapter.execute(in);

        assertThat(out.structuredOutput()).containsEntry("riskLevel", "LOW");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("schema 校验：3 次尝试全失败 → FatalException")
    void schemaValidationExhaustedThrowsFatal() {
        StubChatModel model = new StubChatModel();
        model.content = "no json ever";
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("riskLevel"));
        AgentInput in = new AgentInput("N", "test-agent", "分析风险", new WorkflowContext(),
                Map.of(), List.of(), schema);
        SpringAiAgentAdapter adapter = new SpringAiAgentAdapter(
                client(model), passThroughAdvisors(), List.of(), null, Function.identity(),
                new OutputSchemaValidator());

        assertThatThrownBy(() -> adapter.execute(in))
                .isInstanceOf(FatalException.class)
                .hasMessageContaining("校验失败 3 次");
    }

    @Test
    @DisplayName("schema 校验耗尽：NodeTrace 终态化为 FAILED（不永留 RUNNING，ce-code-review 修复）")
    void schemaExhaustTerminalizesNodeTrace() {
        StubChatModel model = new StubChatModel();
        model.content = "no json ever";
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("riskLevel"));
        AgentInput in = new AgentInput("O", "test-agent", "分析风险", new WorkflowContext(),
                Map.of(), List.of(), schema);
        ExecutionTrace trace = new ExecutionTrace("wf-exhaust");
        SpringAiAgentAdapter adapter = new SpringAiAgentAdapter(
                client(model), passThroughAdvisors(), List.of(), trace, Function.identity(),
                new OutputSchemaValidator());

        assertThatThrownBy(() -> adapter.execute(in)).isInstanceOf(FatalException.class);

        assertThat(trace.nodes()).hasSize(1);
        NodeTrace node = trace.nodes().get(0);
        assertThat(node.status()).isEqualTo(NodeTrace.Status.FAILED);
        assertThat(node.isTerminal()).isTrue();
        assertThat(node.error()).contains("校验失败 3 次");
    }
}
