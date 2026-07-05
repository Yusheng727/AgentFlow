package com.agentflow.adapters.springai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingAdvisorTest {

    private ChatClientRequest request(String promptText) {
        return ChatClientRequest.builder()
                .prompt(Prompt.builder().messages(List.of(new UserMessage(promptText))).build())
                .context(Map.of())
                .build();
    }

    private ChatClientResponse response(String content, int pt, int ct) {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(content))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(pt, ct)).model("gpt-4").build()
        );
        return ChatClientResponse.builder().chatResponse(chatResponse).context(Map.of()).build();
    }

    @Test
    @DisplayName("before/after 各输出一行结构化日志，含 prompt/response + tokens")
    void logsStartAndEnd() {
        List<String> logs = new ArrayList<>();
        LoggingAdvisor advisor = new LoggingAdvisor(Function.identity(), logs::add);

        advisor.before(request("hello world"), null);
        advisor.after(response("done result", 10, 20), null);

        assertThat(logs).hasSize(2);
        assertThat(logs.get(0)).contains("agentflow.agent.call.start").contains("hello world");
        assertThat(logs.get(1)).contains("agentflow.agent.call.end")
                .contains("promptTokens=10").contains("completionTokens=20")
                .contains("model=gpt-4").contains("done result");
    }

    @Test
    @DisplayName("redactor 脱敏 prompt 与 response（U14 seam）")
    void redactsPromptAndResponse() {
        List<String> logs = new ArrayList<>();
        Function<String, String> redactor = s -> s == null ? null : s.replaceAll("sk-\\w+", "sk-***");
        LoggingAdvisor advisor = new LoggingAdvisor(redactor, logs::add);

        advisor.before(request("key=sk-12345 secret"), null);
        advisor.after(response("resp sk-9999", 1, 1), null);

        assertThat(logs.get(0)).contains("sk-***").doesNotContain("sk-12345");
        assertThat(logs.get(1)).contains("sk-***").doesNotContain("sk-9999");
    }

    @Test
    @DisplayName("null request/response → 不抛、不输出")
    void nullSafe() {
        List<String> logs = new ArrayList<>();
        LoggingAdvisor advisor = new LoggingAdvisor(Function.identity(), logs::add);

        advisor.before(null, null);
        advisor.after(null, null);
        assertThat(logs).isEmpty();
    }

    @Test
    @DisplayName("getName / getOrder 返回约定值")
    void nameAndOrder() {
        LoggingAdvisor advisor = new LoggingAdvisor();
        assertThat(advisor.getName()).isEqualTo("LoggingAdvisor");
        assertThat(advisor.getOrder()).isNotZero();
    }
}
