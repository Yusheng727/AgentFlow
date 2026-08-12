package com.agentflow.adapters.langchain4j;

import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.FatalException;
import com.agentflow.agent.TransientException;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.observability.AgentFlowMetrics;
import com.agentflow.observability.ExecutionTrace;
import com.agentflow.observability.ExecutionTraceRegistry;
import com.agentflow.observability.WorkflowBudget;
import com.agentflow.prompt.OutputSchemaValidator;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecutor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * LangChain4jAgentAdapter 单元测试（v1.1 R5/KTD-7）。
 *
 * <p>与 SpringAiAgentAdapterTest 对齐的窄表面契约验证：content/usage 提取、SpEL 解析、
 * 异常映射（Transient/Fatal）、trace、工具执行循环、cancel。stub ChatModel 无真实 API。
 */
class LangChain4jAgentAdapterTest {

    /** 固定响应 stub：content + TokenUsage(10,20)。 */
    static class StubChatModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("stub-content"))
                    .tokenUsage(new TokenUsage(10, 20))
                    .build();
        }
    }

    private static AgentInput input(String nodeId, String template, WorkflowContext ctx) {
        return AgentInput.of(nodeId, "lc4j-agent", template, ctx, Map.of());
    }

    // ─────────────────── 基础通路 ───────────────────

    @Test
    @DisplayName("happy path：content 透传 + usage 写入 metadata（promptTokens/completionTokens/totalTokens）")
    void happyPathContentAndUsage() throws Exception {
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(new StubChatModel());
        AgentOutput out = adapter.execute(input("A", "分析报告", new WorkflowContext()));

        assertThat(out.content()).isEqualTo("stub-content");
        assertThat(out.metadata()).containsEntry("promptTokens", 10L);
        assertThat(out.metadata()).containsEntry("completionTokens", 20L);
        assertThat(out.metadata()).containsEntry("totalTokens", 30L);
    }

    @Test
    @DisplayName("usage 为 null（部分 provider 不返回）→ tokens 记 0，不 NPE")
    void nullUsageYieldsZeroTokens() throws Exception {
        ChatModel noUsage = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("x")).build();
            }
        };
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(noUsage);
        AgentOutput out = adapter.execute(input("B", "t", new WorkflowContext()));

        assertThat(out.content()).isEqualTo("x");
        assertThat(out.metadata()).containsEntry("totalTokens", 0L);
    }

    // ─────────────────── SpEL 解析 ───────────────────

    @Test
    @DisplayName("SpEL 解析 ${context.x} / ${inputs.x}（与 Spring 适配器同 core 解析路径）")
    void spelResolutionWorks() throws Exception {
        // stub 回显最后一条 user message 的文本，验证解析后的 prompt
        ChatModel echo = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                List<ChatMessage> msgs = request.messages();
                String text = ((UserMessage) msgs.get(0)).singleText();
                return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
            }
        };
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(echo);
        WorkflowContext ctx = new WorkflowContext(Map.of("finance", Map.of("riskScore", "HIGH")));
        AgentInput in = AgentInput.of("C", "lc4j", "风险=${context.finance.riskScore} 供应商=${inputs.supplier}",
                ctx, Map.of("supplier", "Acme"));
        AgentOutput out = adapter.execute(in);

        assertThat(out.content()).isEqualTo("风险=HIGH 供应商=Acme");
    }

    @Test
    @DisplayName("SpEL 安全违例（T() 类引用）→ FatalException")
    void spelSecurityViolationFatal() {
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(new StubChatModel());
        assertThatThrownBy(() -> adapter.execute(input("D", "${T(java.lang.System).exit(0)}", new WorkflowContext())))
                .isInstanceOf(FatalException.class)
                .hasMessageContaining("SpEL");
    }

    // ─────────────────── 异常映射（ErrorClassifier 单一真相源） ───────────────────

    @Test
    @DisplayName("LLM 调用抛 IOException 包装 → TransientException（可重试）")
    void ioExceptionMapsToTransient() {
        ChatModel netDown = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new RuntimeException(new IOException("connection reset"));
            }
        };
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(netDown);
        assertThatThrownBy(() -> adapter.execute(input("E", "t", new WorkflowContext())))
                .isInstanceOf(TransientException.class);
    }

    @Test
    @DisplayName("LLM 调用抛一般 RuntimeException → FatalException（不重试）")
    void genericExceptionMapsToFatal() {
        ChatModel boom = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new IllegalStateException("bad request");
            }
        };
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(boom);
        assertThatThrownBy(() -> adapter.execute(input("F", "t", new WorkflowContext())))
                .isInstanceOf(FatalException.class);
    }

    // ─────────────────── B2：LC4j 框架异常分类（composed 注入 Retriable 规则） ───────────────────

    @Test
    @DisplayName("LC4j RateLimitException（真实 cause 形状：marker 外层包裹 HttpException）→ TransientException（可重试）")
    void lc4jRateLimitMapsToTransient() {
        ChatModel rateLimited = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                // 生产真实形状：ExceptionMapper 用 new RateLimitException(httpException)（marker 在外层、内层 HttpException）。
                // 若适配器 unwrap 后再分类会剥掉 marker → 误判 fatal（review B2 P1）。此测试锁定该交互。
                throw new dev.langchain4j.exception.RateLimitException(
                        new dev.langchain4j.exception.HttpException(429, "rate limit exceeded"));
            }
        };
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(rateLimited);
        assertThatThrownBy(() -> adapter.execute(input("F1", "t", new WorkflowContext())))
                .isInstanceOf(TransientException.class);
    }

    @Test
    @DisplayName("LC4j AuthenticationException（NonRetriable 子类）→ FatalException（不重试）")
    void lc4jAuthenticationMapsToFatal() {
        ChatModel unauthorized = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new dev.langchain4j.exception.AuthenticationException("401 unauthorized");
            }
        };
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(unauthorized);
        assertThatThrownBy(() -> adapter.execute(input("F2", "t", new WorkflowContext())))
                .isInstanceOf(FatalException.class);
    }

    // ─────────────────── 工具执行循环 ───────────────────

    @Test
    @DisplayName("工具循环：模型第 1 轮发起 toolExecutionRequest → 本地执行回填 → 第 2 轮给文本，usage 跨轮累加")
    void toolExecutionLoop() throws Exception {
        AtomicInteger toolCalls = new AtomicInteger(0);
        AtomicInteger chatCalls = new AtomicInteger(0);

        // 工具 bean：@Tool 方法被调用时计数
        class TimeTools {
            @Tool("返回当前时间")
            String currentTime() {
                toolCalls.incrementAndGet();
                return "12:00";
            }
        }

        ChatModel toolModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                int call = chatCalls.incrementAndGet();
                if (call == 1) {
                    // 第 1 轮：模型要求调工具
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                                    .id("req-1").name("currentTime").arguments("{}").build()))
                            .tokenUsage(new TokenUsage(10, 5))
                            .build();
                }
                // 第 2 轮：消息里应有 ToolExecutionResultMessage（工具结果 12:00）
                boolean hasToolResult = request.messages().stream()
                        .anyMatch(m -> m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage tr
                                && tr.text().contains("12:00"));
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(hasToolResult ? "现在 12:00" : "no-tool-result"))
                        .tokenUsage(new TokenUsage(20, 8))
                        .build();
            }
        };

        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(toolModel, List.of(new TimeTools()), null, null);
        AgentOutput out = adapter.execute(input("G", "现在几点", new WorkflowContext()));

        assertThat(toolCalls.get()).isEqualTo(1);                 // 工具被本地执行一次
        assertThat(chatCalls.get()).isEqualTo(2);                 // 两轮 chat
        assertThat(out.content()).isEqualTo("现在 12:00");         // 第 2 轮文本
        assertThat(out.metadata()).containsEntry("promptTokens", 30L);      // 10+20 跨轮累加
        assertThat(out.metadata()).containsEntry("completionTokens", 13L);  // 5+8
        assertThat(out.metadata()).containsEntry("totalTokens", 43L);
    }

    @Test
    @DisplayName("B1 安全执行器：@Tool 抛含敏感信息异常 → 返回泛化错误，不泄漏原始异常细节（SEC-1）")
    void safeToolExecutorReturnsGenericErrorOnThrow() throws Exception {
        class LeakyTools {
            @Tool("Risky tool")
            String risky() {
                throw new IllegalStateException("DB conn=jdbc:postgresql://internal:5432/prod pwd=secret123");
            }
        }
        LeakyTools bean = new LeakyTools();
        Method m = bean.getClass().getDeclaredMethod("risky");
        ToolExecutor exec = new LangChain4jAgentAdapter.SafeToolExecutor(bean, m);

        String result = exec.execute(
                ToolExecutionRequest.builder().id("r1").name("risky").arguments("{}").build(), null);

        assertThat(result).doesNotContain("jdbc:", "secret123");     // 敏感细节不泄漏给模型
        assertThat(result).contains("tool execution failed");          // 但给模型可理解的泛化错误
    }

    @Test
    @DisplayName("B1 工具循环：@Tool 抛异常 → 回填模型的工具结果不含原始异常细节（端到端 SEC-1）")
    void throwingToolInLoopDoesNotLeakToModel() throws Exception {
        AtomicBoolean leaked = new AtomicBoolean(true); // 默认认为泄漏，若模型收到原始细节保持 true
        ChatModel model = new ChatModel() {
            int call = 0;

            @Override
            public ChatResponse chat(ChatRequest request) {
                call++;
                if (call == 1) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                                    .id("req-1").name("risky").arguments("{}").build()))
                            .tokenUsage(new TokenUsage(10, 5)).build();
                }
                boolean hasDetail = request.messages().stream()
                        .filter(m -> m instanceof ToolExecutionResultMessage)
                        .anyMatch(m -> ((ToolExecutionResultMessage) m).text().contains("secret123"));
                leaked.set(hasDetail);
                return ChatResponse.builder().aiMessage(AiMessage.from("done"))
                        .tokenUsage(new TokenUsage(20, 8)).build();
            }
        };
        class LeakyTools {
            @Tool("Risky")
            String risky() {
                throw new IllegalStateException("DB pwd=secret123 host=internal-db");
            }
        }
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(model, List.of(new LeakyTools()), null, null);
        adapter.execute(input("H", "触发工具", new WorkflowContext()));

        assertThat(leaked.get()).isFalse(); // 原始异常细节未经工具结果外泄给模型
    }

    @Test
    @DisplayName("模型发起未知工具 → error JSON 回填（不中断），模型下轮仍能给文本")
    void unknownToolFedBackAsError() throws Exception {
        AtomicInteger chatCalls = new AtomicInteger(0);
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                if (chatCalls.incrementAndGet() == 1) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                                    .id("r").name("notRegistered").arguments("{}").build()))
                            .tokenUsage(new TokenUsage(1, 1))
                            .build();
                }
                boolean sawError = request.messages().stream()
                        .anyMatch(m -> m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage tr
                                && tr.text().contains("tool not registered"));
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(sawError ? "handled" : "lost"))
                        .tokenUsage(new TokenUsage(1, 1))
                        .build();
            }
        };
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(model); // 未注册任何工具
        AgentOutput out = adapter.execute(input("H", "t", new WorkflowContext()));
        assertThat(out.content()).isEqualTo("handled");
    }

    @Test
    @DisplayName("工具死循环防护：达 MAX_TOOL_ROUNDS 仍未完成 → 判 FatalException（不静默 SUCCESS-with-null）")
    void toolLoopBoundedFailsNode() {
        ChatModel infiniteTools = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                                .id("r").name("notRegistered").arguments("{}").build()))
                        .tokenUsage(new TokenUsage(1, 1))
                        .build();
            }
        };
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(infiniteTools);
        assertThatThrownBy(() -> adapter.execute(input("I", "t", new WorkflowContext())))
                .isInstanceOf(FatalException.class)
                .hasMessageContaining("轮数达上限");
    }

    @Test
    @DisplayName("注入 metrics → 记账令 tokens.consumed/cost.estimated 可观测（Grafana 数据源）")
    void metricsRecordedWhenProvided() throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new AgentFlowMetrics(registry);
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(
                new StubChatModel(), List.of(), null, null, metrics, "gpt-4o-mini");
        adapter.execute(input("M", "t", new WorkflowContext())); // stub tokens 10/20

        assertThat(registry.counter(AgentFlowMetrics.TOKENS_CONSUMED, "agent", "lc4j-agent", "model", "gpt-4o-mini")
                .count()).isEqualTo(30.0);
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_ESTIMATED, "model", "gpt-4o-mini")
                .count()).isEqualTo(0.0000135, within(1e-9)); // 10 in@$0.15 + 20 out@$0.60 per 1M
    }

    // ─────────────────── C2：结构化输出 schema 校验（core OutputSchemaValidator 复用） ───────────────────

    private static final OutputSchemaValidator VALIDATOR = new OutputSchemaValidator();

    /** schema: {type:object, properties:{x:{type:integer}}, required:[x]} */
    private static Map<String, Object> intSchema() {
        return Map.of("type", "object",
                "properties", Map.of("x", Map.of("type", "integer")),
                "required", List.of("x"));
    }

    private static AgentInput inputWithSchema(String nodeId, String template, Map<String, Object> schema) {
        return new AgentInput(nodeId, "lc4j-agent", template, new WorkflowContext(),
                Map.of(), List.of(), schema, null, null, null);
    }

    @Test
    @DisplayName("schema 校验成功：structuredOutput 正确填充（补上 KTD-7 对价，不再恒空）")
    void schemaValidationFillsStructuredOutput() throws Exception {
        ChatModel jsonChat = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("{\"x\": 42}"))
                        .tokenUsage(new TokenUsage(3, 5))
                        .build();
            }
        };
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(
                jsonChat, List.of(), null, null, null, null, VALIDATOR);
        AgentOutput out = adapter.execute(inputWithSchema("S1", "返回 x", intSchema()));

        assertThat(out.content()).contains("\"x\"");
        assertThat(out.structuredOutput()).containsEntry("x", 42);
        assertThat(out.metadata()).containsEntry("promptTokens", 3L);
    }

    @Test
    @DisplayName("schema 反馈重试：首次无效 JSON，带反馈重调第二次合法 → 通过（回调调用 2 次）")
    void schemaFeedbackRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChatModel chat = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                int n = calls.incrementAndGet();
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(n == 1 ? "无法生成 JSON" : "{\"x\": 7}"))
                        .tokenUsage(new TokenUsage(1, 1))
                        .build();
            }
        };
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(
                chat, List.of(), null, null, null, null, VALIDATOR);
        AgentOutput out = adapter.execute(inputWithSchema("S2", "返回 x", intSchema()));

        assertThat(out.structuredOutput()).containsEntry("x", 7);
        assertThat(calls.get()).isEqualTo(2); // 首次 + 1 次反馈重试
    }

    @Test
    @DisplayName("schema 校验耗尽：3 次全失败 → FatalException（不静默成功）")
    void schemaExhaustedThrowsFatal() {
        ChatModel chat = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("永远不是 JSON")).build();
            }
        };
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(
                chat, List.of(), null, null, null, null, VALIDATOR);
        assertThatThrownBy(() -> adapter.execute(inputWithSchema("S3", "返回 x", intSchema())))
                .isInstanceOf(FatalException.class)
                .hasMessageContaining("校验失败");
    }

    // ─────────────────── trace / cancel ───────────────────

    @Test
    @DisplayName("input.trace() 非空 → NodeTrace 追加并 SUCCESS（workflow 级 trace 路径）")
    void inputTraceRecorded() throws Exception {
        ExecutionTraceRegistry registry = new ExecutionTraceRegistry();
        ExecutionTrace trace = registry.register("wf-lc4j");
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(new StubChatModel());
        AgentInput in = new AgentInput("J", "lc4j", "t", new WorkflowContext(),
                Map.of(), List.of(), Map.of(), null, trace);
        adapter.execute(in);

        ExecutionTrace.Snapshot snapshot = registry.snapshot("wf-lc4j");
        assertThat(snapshot.nodes()).hasSize(1);
        assertThat(snapshot.nodes().get(0).nodeId()).isEqualTo("J");
        assertThat(snapshot.nodes().get(0).status())
                .isEqualTo(com.agentflow.observability.NodeTrace.Status.SUCCESS);
    }

    @Test
    @DisplayName("cancel() 不抛异常（best-effort noop，实际中止在 NodeExecutor future.cancel）")
    void cancelIsBestEffortNoop() {
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(new StubChatModel());
        adapter.cancel(input("K", "t", new WorkflowContext()));
        adapter.cancel(null); // null 防御
    }

    @Test
    @DisplayName("C1：AgentInput 携带 budget → 真实路径累进预算，首次超限触发 budget_exceeded 一次")
    void perWorkflowBudgetCostExceededFiresOnce() throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new AgentFlowMetrics(registry);
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(
                new StubChatModel(), List.of(), null, null, metrics, "gpt-4o-mini");
        WorkflowBudget budget = new WorkflowBudget(null, 0.0000001); // 极小成本预算（stub 成本 ≈0.0000135）
        AgentInput in = new AgentInput("B1", "lc4j-agent", "t", new WorkflowContext(),
                Map.of(), List.of(), Map.of(), null, null, budget);

        adapter.execute(in); // stub tokens 10/20 → 成本超预算

        assertThat(budget.isExceeded()).isTrue();
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("C1：AgentInput 携带 budget 未超限 → 不触发 budget_exceeded")
    void perWorkflowBudgetWithinLimitNoEvent() throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new AgentFlowMetrics(registry);
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(
                new StubChatModel(), List.of(), null, null, metrics, "gpt-4o-mini");
        WorkflowBudget budget = new WorkflowBudget(1_000_000L, 100.0);
        AgentInput in = new AgentInput("B2", "lc4j-agent", "t", new WorkflowContext(),
                Map.of(), List.of(), Map.of(), null, null, budget);

        adapter.execute(in);

        assertThat(budget.isExceeded()).isFalse();
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_COST_BUDGET_EXCEEDED).count()).isZero();
    }

    @Test
    @DisplayName("SafeToolExecutor 参数解析/渲染：typed 参数强转 + void/primitive/null 结果（对齐 DefaultToolExecutor 语义）")
    void safeToolExecutorTypedArgsAndRendering() throws Exception {
        class CalcTools {
            @Tool
            String summarize(String prefix, int count, long qty, boolean urgent, double rate) {
                return prefix + "|" + count + "|" + qty + "|" + urgent + "|" + rate;
            }
            @Tool
            void silent() { }
            @Tool
            int answer() { return 7; }
            @Tool
            String nothing() { return null; }
            record Item(String name, int qty) { }
            @Tool
            String pack(Item item) { return item.name(); }
        }
        CalcTools bean = new CalcTools();

        // typed 参数从 JSON 强转 + String 结果渲染
        Method summarize = bean.getClass().getDeclaredMethod(
                "summarize", String.class, int.class, long.class, boolean.class, double.class);
        ToolExecutor exec = new LangChain4jAgentAdapter.SafeToolExecutor(bean, summarize);
        String r = exec.execute(ToolExecutionRequest.builder().id("a").name("summarize")
                .arguments("{\"prefix\":\"P\",\"count\":3,\"qty\":100,\"urgent\":true,\"rate\":2.5}")
                .build(), null);
        assertThat(r).isEqualTo("P|3|100|true|2.5");

        // void → "Success"
        ToolExecutor se = new LangChain4jAgentAdapter.SafeToolExecutor(bean,
                bean.getClass().getDeclaredMethod("silent"));
        assertThat(se.execute(ToolExecutionRequest.builder().id("v").name("silent").arguments("{}").build(), null))
                .isEqualTo("Success");

        // 非 String 结果 → JSON 渲染
        ToolExecutor ae = new LangChain4jAgentAdapter.SafeToolExecutor(bean,
                bean.getClass().getDeclaredMethod("answer"));
        assertThat(ae.execute(ToolExecutionRequest.builder().id("i").name("answer").arguments("{}").build(), null))
                .isEqualTo("7");

        // null 结果 → "null" 哨兵
        ToolExecutor ne = new LangChain4jAgentAdapter.SafeToolExecutor(bean,
                bean.getClass().getDeclaredMethod("nothing"));
        assertThat(ne.execute(ToolExecutionRequest.builder().id("n").name("nothing").arguments("{}").build(), null))
                .isEqualTo("null");

        // record 参数 → Jackson convertValue
        ToolExecutor pe = new LangChain4jAgentAdapter.SafeToolExecutor(bean,
                bean.getClass().getDeclaredMethod("pack", CalcTools.Item.class));
        assertThat(pe.execute(ToolExecutionRequest.builder().id("p").name("pack")
                .arguments("{\"item\":{\"name\":\"gadget\",\"qty\":2}}").build(), null))
                .isEqualTo("gadget");

        // 畸形 JSON 参数 → 按空参数处理，缺 primitive 参 invoke 抛 → 泛化错误不抛穿
        ToolExecutor mj = new LangChain4jAgentAdapter.SafeToolExecutor(bean, summarize);
        assertThat(mj.execute(ToolExecutionRequest.builder().id("m").name("summarize")
                .arguments("{not json").build(), null)).contains("tool execution failed");
    }

    @Test
    @DisplayName("SafeToolExecutor @ToolMemoryId 参数：memoryId 透传")
    void safeToolExecutorMemoryId() throws Exception {
        class MemTools {
            @Tool
            String who(@ToolMemoryId Long id, String name) { return id + ":" + name; }
        }
        MemTools bean = new MemTools();
        ToolExecutor exec = new LangChain4jAgentAdapter.SafeToolExecutor(bean,
                bean.getClass().getDeclaredMethod("who", Long.class, String.class));
        String r = exec.execute(ToolExecutionRequest.builder().id("w").name("who")
                .arguments("{\"name\":\"n\"}").build(), 42L);
        assertThat(r).isEqualTo("42:n");
    }

    @Test
    @DisplayName("B3 基类上的 @Tool 被注册并可执行（不再遗漏继承方法，ADV-2）")
    void inheritedBaseClassToolRegisteredAndExecuted() throws Exception {
        class BaseTools {
            @Tool("基类工具")
            String inheritedOp() { return "base-ok"; }
        }
        class ChildTools extends BaseTools { }
        AtomicInteger chatCalls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                if (chatCalls.incrementAndGet() == 1) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                                    .id("req-1").name("inheritedOp").arguments("{}").build()))
                            .tokenUsage(new TokenUsage(1, 1)).build();
                }
                boolean hasResult = request.messages().stream()
                        .anyMatch(m -> m instanceof ToolExecutionResultMessage tr && tr.text().contains("base-ok"));
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(hasResult ? "done" : "no-tool-result"))
                        .tokenUsage(new TokenUsage(1, 1)).build();
            }
        };
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(model, List.of(new ChildTools()), null, null);
        AgentOutput out = adapter.execute(input("B3-base", "调用", new WorkflowContext()));

        assertThat(out.content()).isEqualTo("done"); // 模型第 2 轮看到基类工具真的被执行
        assertThat(chatCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("B3 接口上的 @Tool 被注册并可执行（不再遗漏接口方法，ADV-2）")
    void interfaceToolRegisteredAndExecuted() throws Exception {
        interface Tools {
            @Tool("接口工具")
            String ifaceOp();
        }
        class ImplTools implements Tools {
            @Override
            public String ifaceOp() { return "iface-ok"; }
        }
        AtomicInteger chatCalls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                if (chatCalls.incrementAndGet() == 1) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                                    .id("req-2").name("ifaceOp").arguments("{}").build()))
                            .tokenUsage(new TokenUsage(1, 1)).build();
                }
                boolean hasResult = request.messages().stream()
                        .anyMatch(m -> m instanceof ToolExecutionResultMessage tr && tr.text().contains("iface-ok"));
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(hasResult ? "done" : "no-tool-result"))
                        .tokenUsage(new TokenUsage(1, 1)).build();
            }
        };
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(model, List.of(new ImplTools()), null, null);
        AgentOutput out = adapter.execute(input("B3-iface", "调用", new WorkflowContext()));

        assertThat(out.content()).isEqualTo("done"); // 接口工具被执行并回填
        assertThat(chatCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("C1 review coerce：字符串型数字参数可转（{\"count\":\"3\"}→int 3），不再 ClassCastException")
    void coerceCoercesStringNumericArgs() throws Exception {
        class CalcTools {
            @Tool
            int add(int count, int base) { return count + base; }
        }
        CalcTools bean = new CalcTools();
        ToolExecutor exec = new LangChain4jAgentAdapter.SafeToolExecutor(bean,
                bean.getClass().getDeclaredMethod("add", int.class, int.class));
        String r = exec.execute(ToolExecutionRequest.builder().id("a").name("add")
                .arguments("{\"count\":\"3\",\"base\":4}").build(), null);
        assertThat(r).isEqualTo("7"); // "3"→3 经 Jackson convertValue，加 base 4
    }

    @Test
    @DisplayName("B3 review 去重：子类重写基类同签名 @Tool → concrete 实现优先（只注册一个 spec）")
    void overriddenToolConcreteWinsDedup() throws Exception {
        class BaseTools {
            @Tool("op")
            String op() { return "base-result"; }
        }
        class ChildTools extends BaseTools {
            @Override
            @Tool("op")
            String op() { return "child-result"; }
        }
        AtomicInteger chatCalls = new AtomicInteger();
        AtomicInteger specCount = new AtomicInteger(-1);
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                if (chatCalls.incrementAndGet() == 1) {
                    specCount.set(request.toolSpecifications() == null ? 0 : request.toolSpecifications().size());
                    return ChatResponse.builder().aiMessage(AiMessage.from(
                            ToolExecutionRequest.builder().id("r").name("op").arguments("{}").build()))
                            .tokenUsage(new TokenUsage(1, 1)).build();
                }
                boolean hasChild = request.messages().stream()
                        .anyMatch(m -> m instanceof ToolExecutionResultMessage tr && tr.text().contains("child-result"));
                return ChatResponse.builder().aiMessage(AiMessage.from(hasChild ? "done" : "no")).build();
            }
        };
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(model, List.of(new ChildTools()), null, null);
        AgentOutput out = adapter.execute(input("DEDUP", "1", new WorkflowContext()));

        assertThat(specCount.get()).isEqualTo(1);       // 同签名只注册一个 spec（不重复 ToolSpecification）
        assertThat(out.content()).isEqualTo("done");     // 具体实现（child）被调用
    }

    @Test
    @DisplayName("B3 review：同名重载 @Tool 只向模型暴露一个 spec（不再重复 ToolSpecification 名）")
    void duplicateToolNameOnlySingleSpec() throws Exception {
        class OverloadTools {
            @Tool
            String op(int x) { return "int:" + x; }
            @Tool
            String op(long x) { return "long:" + x; }
        }
        AtomicInteger chatCalls = new AtomicInteger();
        AtomicInteger specCount = new AtomicInteger(-1);
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                chatCalls.incrementAndGet();
                specCount.set(request.toolSpecifications() == null ? 0 : request.toolSpecifications().size());
                return ChatResponse.builder().aiMessage(AiMessage.from("done"))
                        .tokenUsage(new TokenUsage(1, 1)).build();
            }
        };
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(model, List.of(new OverloadTools()), null, null);
        adapter.execute(input("DUP", "hi", new WorkflowContext()));

        assertThat(specCount.get()).isEqualTo(1); // 重载同名只注册一个 spec
    }

    @Test
    @DisplayName("C1 review interrupt：工具抛 InterruptedException → 恢复中断标志（不吞）")
    void interruptionRestoresFlag() throws Exception {
        class InterruptTool {
            @Tool
            String blocking() throws InterruptedException { throw new InterruptedException("cancel"); }
        }
        InterruptTool bean = new InterruptTool();
        ToolExecutor exec = new LangChain4jAgentAdapter.SafeToolExecutor(bean,
                bean.getClass().getDeclaredMethod("blocking"));
        String r = exec.execute(ToolExecutionRequest.builder().id("i").name("blocking").arguments("{}").build(), null);

        assertThat(r).contains("tool execution failed");
        assertThat(Thread.currentThread().isInterrupted()).isTrue(); // 标志被恢复
        Thread.interrupted(); // 清标志避免污染后续测试
    }

    @Test
    @DisplayName("C1 review 预算：schema 重试每次尝试的用量都计入预算（非仅末次）")
    void schemaRetryBudgetAccumulatesAllAttempts() throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new AgentFlowMetrics(registry);
        ChatModel model = new ChatModel() {
            int call = 0;
            @Override
            public ChatResponse chat(ChatRequest request) {
                call++;
                String text = call < 3 ? "not-json-at-all" : "{\"riskLevel\":\"HIGH\"}";
                return ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(new TokenUsage(10, call)).build();
            }
        };
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("riskLevel", Map.of("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH"))));
        schema.put("required", List.of("riskLevel"));
        WorkflowBudget budget = new WorkflowBudget(null, 100.0);
        AgentInput in = new AgentInput("S1", "lc4j-agent", "t", new WorkflowContext(),
                Map.of(), List.of(), schema, null, null, budget);
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(model, List.of(), null, null,
                metrics, "gpt-4o-mini", new OutputSchemaValidator());
        AgentOutput out = adapter.execute(in);

        // 三次尝试全计入预算/token：10 in×3 + (1+2+3) out；末次 metadata 语义保留
        // gpt-4o-mini: in $0.15 / out $0.60 per 1M
        assertThat(budget.cost()).isCloseTo((30.0 * 0.15 + 6.0 * 0.60) / 1_000_000.0, within(1e-9));
        assertThat(registry.counter(AgentFlowMetrics.TOKENS_CONSUMED,
                "agent", "lc4j-agent", "model", "gpt-4o-mini").count()).isEqualTo(36.0);
        assertThat(out.metadata()).containsEntry("totalTokens", 13L); // 末次 10+3
    }

    @Test
    @DisplayName("C1 review 预算：工具链耗尽（Fatal）后已付费轮次仍计入预算与指标")
    void failurePathTokensChargedToBudget() throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new AgentFlowMetrics(registry);
        AtomicInteger chatCalls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                chatCalls.incrementAndGet();
                // 每轮都要求未知工具 → 达到 MAX_TOOL_ROUNDS 驱动 FatalException（各轮已计费）
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(ToolExecutionRequest.builder().id("r").name("noSuchTool")
                                .arguments("{}").build()))
                        .tokenUsage(new TokenUsage(10, 5)).build();
            }
        };
        WorkflowBudget budget = new WorkflowBudget(null, 100.0);
        AgentInput in = new AgentInput("F1", "lc4j-agent", "t", new WorkflowContext(),
                Map.of(), List.of(), Map.of(), null, null, budget);
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(model, List.of(), null, null,
                metrics, "gpt-4o-mini");
        assertThatThrownBy(() -> adapter.execute(in)).isInstanceOf(FatalException.class);

        assertThat(chatCalls.get()).isEqualTo(5); // MAX_TOOL_ROUNDS=5 轮
        // 5 轮 × (10 in + 5 out) 即使节点失败也计入预算与 token
        assertThat(budget.cost()).isCloseTo((10.0 * 0.15 + 5.0 * 0.60) * 5 / 1_000_000.0, within(1e-9));
        assertThat(registry.counter(AgentFlowMetrics.TOKENS_CONSUMED,
                "agent", "lc4j-agent", "model", "gpt-4o-mini").count()).isEqualTo(75.0);
    }
}
