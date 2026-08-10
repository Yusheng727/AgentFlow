package com.agentflow.adapters.langchain4j;

import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.FatalException;
import com.agentflow.agent.TransientException;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.observability.ExecutionTrace;
import com.agentflow.observability.ExecutionTraceRegistry;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                                && tr.text().contains("unknown tool"));
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
    @DisplayName("工具死循环防护：模型每轮都发起工具请求 → 达 MAX_TOOL_ROUNDS 截断返回")
    void toolLoopBounded() throws Exception {
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
        AgentOutput out = adapter.execute(input("I", "t", new WorkflowContext()));
        // 不抛异常，正常返回（content 可能为 null——模型未给文本）
        assertThat(out.metadata()).containsEntry("totalTokens", (long) LangChain4jAgentAdapter.MAX_TOOL_ROUNDS * 2);
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
}
