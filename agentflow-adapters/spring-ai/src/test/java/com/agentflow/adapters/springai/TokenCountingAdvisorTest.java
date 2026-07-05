package com.agentflow.adapters.springai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenCountingAdvisorTest {

    private ChatClientResponse responseWith(int promptTok, int completionTok, String model) {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok"))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(promptTok, completionTok))
                        .model(model)
                        .build()
        );
        return ChatClientResponse.builder().chatResponse(chatResponse).context(Map.of()).build();
    }

    private ChatClientRequest request() {
        return ChatClientRequest.builder()
                .prompt(org.springframework.ai.chat.prompt.Prompt.builder()
                        .messages(List.of(new UserMessage("hi")))
                        .build())
                .context(Map.of())
                .build();
    }

    @Test
    @DisplayName("after：累加 totalTokens 到 agentflow.tokens.consumed{agent,model}")
    void countsTokens() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TokenCountingAdvisor advisor = new TokenCountingAdvisor(registry, "finance-agent");

        advisor.before(request(), null);
        advisor.after(responseWith(10, 20, "gpt-4"), null);

        double count = registry.counter(TokenCountingAdvisor.COUNTER_NAME,
                "agent", "finance-agent", "model", "gpt-4").count();
        assertThat(count).isEqualTo(30.0);
    }

    @Test
    @DisplayName("多次调用累加，不覆盖")
    void accumulatesAcrossCalls() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TokenCountingAdvisor advisor = new TokenCountingAdvisor(registry, "a");

        advisor.after(responseWith(5, 5, "m1"), null);
        advisor.after(responseWith(10, 10, "m1"), null);

        double count = registry.counter(TokenCountingAdvisor.COUNTER_NAME,
                "agent", "a", "model", "m1").count();
        assertThat(count).isEqualTo(30.0);  // 10 + 20
    }

    @Test
    @DisplayName("不同 model 分别计数")
    void separatesByModelTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TokenCountingAdvisor advisor = new TokenCountingAdvisor(registry, "a");

        advisor.after(responseWith(1, 2, "m1"), null);
        advisor.after(responseWith(3, 4, "m2"), null);

        assertThat(registry.counter(TokenCountingAdvisor.COUNTER_NAME,
                "agent", "a", "model", "m1").count()).isEqualTo(3.0);
        assertThat(registry.counter(TokenCountingAdvisor.COUNTER_NAME,
                "agent", "a", "model", "m2").count()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("null MeterRegistry → after 不抛（no-op）")
    void nullRegistryNoOp() {
        TokenCountingAdvisor advisor = new TokenCountingAdvisor(null, "a");
        advisor.after(responseWith(10, 20, "m"), null);  // 不抛
    }

    @Test
    @DisplayName("null/缺 usage 的 response → 不抛、不计数")
    void nullUsageNoOp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TokenCountingAdvisor advisor = new TokenCountingAdvisor(registry, "a");
        advisor.after(null, null);  // 不抛
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    @DisplayName("null agentName → tag 用 'unknown'")
    void nullAgentNameTaggedUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TokenCountingAdvisor advisor = new TokenCountingAdvisor(registry, null);

        advisor.after(responseWith(1, 1, "m"), null);

        double count = registry.counter(TokenCountingAdvisor.COUNTER_NAME,
                "agent", "unknown", "model", "m").count();
        assertThat(count).isEqualTo(2.0);
    }
}
