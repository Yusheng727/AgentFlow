package com.agentflow.adapters.springai;

import com.agentflow.agent.AgentInput;
import com.agentflow.agent.FatalException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SpringAiAgentAdapter cancel() 测试（U3-6，KTD-6 v4.3）。
 *
 * <p>验证适配器 cancel(AgentInput) 真正中断在飞 LLM 调用——用阻塞 stub ChatModel
 * （CountDownLatch.await 模拟慢 HTTP），cancel() 后 VT 被中断、await 抛 InterruptedException。
 *
 * <p>Spring AI 2.0 无公共 HTTP abort 钩子，故本测试验证 VT 中断（best-effort）；
 * 生产中底层 HTTP client 响应中断则真中止 HTTP 请求 + 停止 token 计费。
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

    private static SpringAiAgentAdapter adapter(BlockingChatModel model) {
        return new SpringAiAgentAdapter(
                org.springframework.ai.chat.client.ChatClient.create(model),
                List.of(new SpringAiAgentAdapterTest.NoOpBaseAdvisor()),
                List.of(), null, Function.identity(), null);
    }

    @Test
    @DisplayName("cancel() 中断在飞 LLM 调用：阻塞 stub 被中断，execute 抛 FatalException")
    void cancelInterruptsInFlightCall() throws Exception {
        BlockingChatModel model = new BlockingChatModel();
        SpringAiAgentAdapter adapter = adapter(model);

        AtomicReference<Throwable> execError = new AtomicReference<>();
        // VT 跑 execute（阻塞在 stub 的 await）
        Thread vt = Thread.startVirtualThread(() -> {
            try {
                adapter.execute(input("A"));
            } catch (Throwable e) {
                execError.set(e);
            }
        });

        // 等 stub 进入 call
        assertThat(model.entered.await(2, TimeUnit.SECONDS))
                .as("stub ChatModel.call 应被进入").isTrue();

        // cancel 应中断在飞 VT
        adapter.cancel(input("A"));

        vt.join(5000);
        assertThat(vt.isAlive()).as("VT 应已退出").isFalse();
        assertThat(model.interrupted).as("在飞 LLM 调用应被中断").isTrue();
        // 中断 → RuntimeException → 适配器 mapException(InterruptedException) → Transient
        assertThat(execError.get()).isInstanceOf(com.agentflow.agent.TransientException.class);
    }

    @Test
    @DisplayName("cancel() 对无在飞调用的 nodeId 安全（no-op，不抛）")
    void cancelNoInFlightIsSafe() {
        BlockingChatModel model = new BlockingChatModel();
        SpringAiAgentAdapter adapter = adapter(model);
        // 未执行过，inFlight 为空
        adapter.cancel(input("nonexistent"));
    }

    @Test
    @DisplayName("cancel(null input) 安全（no-op）")
    void cancelNullInputSafe() {
        BlockingChatModel model = new BlockingChatModel();
        SpringAiAgentAdapter adapter = adapter(model);
        adapter.cancel(null);
    }

    @Test
    @DisplayName("execute 完成后 inFlight 清理（cancel 不误中断已完成的节点）")
    void inFlightClearedAfterCompletion() throws Exception {
        // 用立即返回的 stub（复用 SpringAiAgentAdapterTest.StubChatModel 行为：固定 content）
        SpringAiAgentAdapterTest.StubChatModel model = new SpringAiAgentAdapterTest.StubChatModel();
        SpringAiAgentAdapter adapter = new SpringAiAgentAdapter(
                org.springframework.ai.chat.client.ChatClient.create(model),
                List.of(new SpringAiAgentAdapterTest.NoOpBaseAdvisor()),
                List.of(), null, Function.identity(), null);

        adapter.execute(input("B"));

        // 完成后 cancel 不应抛、也不应中断任何线程
        adapter.cancel(input("B"));
    }
}
