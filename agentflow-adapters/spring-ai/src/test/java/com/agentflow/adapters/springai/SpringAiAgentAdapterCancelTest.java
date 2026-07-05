package com.agentflow.adapters.springai;

import com.agentflow.agent.AgentInput;
import com.agentflow.engine.WorkflowContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SpringAiAgentAdapter cancel/中断 测试（U3-6，KTD-6 v4.3）。
 *
 * <p>Spring AI 2.0 ChatClient.call() 同步阻塞、无公共 HTTP abort 钩子——适配器 cancel() 为
 * best-effort no-op（记 warn），实际中止由 NodeExecutor 的 future.cancel(true) 中断 VT 完成。
 * 本测试验证：(1) cancel() 自身安全；(2) 适配器的阻塞 LLM 调用响应 VT 中断（生产中止路径）。
 */
class SpringAiAgentAdapterCancelTest {

    /** 阻塞 stub：call 进入后 await 一个永不释放的 latch，被中断则记录并抛 RuntimeException。 */
    static class BlockingChatModel implements ChatModel {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch blockForever = new CountDownLatch(1);
        volatile boolean interrupted;

        @Override
        public ChatResponse call(Prompt prompt) {
            entered.countDown();
            try {
                blockForever.await();
            } catch (InterruptedException e) {
                interrupted = true;
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted in flight", e);
            }
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage("never"))),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build()
            );
        }
    }

    private static AgentInput input(String nodeId) {
        return new AgentInput(nodeId, "test-agent", "hi", new WorkflowContext(),
                Map.of(), List.of(), Map.of());
    }

    private static SpringAiAgentAdapter adapter(ChatModel model) {
        return new SpringAiAgentAdapter(
                org.springframework.ai.chat.client.ChatClient.create(model),
                List.of(new SpringAiAgentAdapterTest.NoOpBaseAdvisor()),
                List.of(), null, Function.identity(), null);
    }

    @Test
    @DisplayName("cancel() 安全：null input 与无在飞调用均不抛（best-effort no-op）")
    void cancelIsSafeNoOp() {
        SpringAiAgentAdapter adapter = adapter(new BlockingChatModel());
        adapter.cancel(null);                      // null input
        adapter.cancel(input("nonexistent"));      // 无在飞调用
    }

    @Test
    @DisplayName("VT 中断中止在飞 LLM 调用：future.cancel(true) → 阻塞 stub 被中断 → execute 抛 Transient")
    void vtInterruptAbortsInFlightCall() throws Exception {
        BlockingChatModel model = new BlockingChatModel();
        SpringAiAgentAdapter adapter = adapter(model);

        AtomicReference<Throwable> execError = new AtomicReference<>();
        // 模拟 NodeExecutor：把 agent.execute 提交到 VT executor，拿 Future
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> future = exec.submit(() -> {
                try {
                    adapter.execute(input("A"));
                } catch (Throwable e) {
                    execError.set(e);
                }
            });

            // 等 stub 进入 call
            assertThat(model.entered.await(2, TimeUnit.SECONDS))
                    .as("stub ChatModel.call 应被进入").isTrue();

            // NodeExecutor 超时路径：future.cancel(true) 中断 VT
            future.cancel(true);

            // VT 应已退出（stub.await 抛 InterruptedException → RuntimeException → mapException → Transient）
            long deadline = System.currentTimeMillis() + 5000;
            while (execError.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
        }

        assertThat(model.interrupted).as("在飞 LLM 调用应被 VT 中断").isTrue();
        assertThat(execError.get()).isInstanceOf(com.agentflow.agent.TransientException.class);
    }

    @Test
    @DisplayName("execute 完成后调用 cancel() 安全（不影响已完成节点）")
    void cancelAfterCompletionSafe() throws Exception {
        SpringAiAgentAdapterTest.StubChatModel model = new SpringAiAgentAdapterTest.StubChatModel();
        SpringAiAgentAdapter adapter = new SpringAiAgentAdapter(
                org.springframework.ai.chat.client.ChatClient.create(model),
                List.of(new SpringAiAgentAdapterTest.NoOpBaseAdvisor()),
                List.of(), null, Function.identity(), null);

        adapter.execute(input("B"));
        adapter.cancel(input("B"));  // 完成后 cancel，不抛、不误中断
    }
}
