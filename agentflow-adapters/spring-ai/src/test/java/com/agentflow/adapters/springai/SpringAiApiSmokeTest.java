package com.agentflow.adapters.springai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.core.Ordered;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KTD-7 冒烟测试（plan U3 前置 gate）：验证 Spring AI 2.0 GA 的
 * ChatClient + Advisor Chain（BaseAdvisor before/after）+ @Tool 注册 API 表面，
 * 跑通才开建 SpringAiAgentAdapter。用 stub ChatModel 避免真实 API key。
 */
class SpringAiApiSmokeTest {

    /** stub ChatModel：返回固定响应 + DefaultUsage(10,20)，验证 token 捕获链路。 */
    static class StubChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage("smoke-ok"))),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(10, 20)).build()
            );
        }
    }

    /** 自定义 BaseAdvisor：记录 before/after 调用 + 从 after 捕获 usage（TokenCountingAdvisor 雏形）。 */
    static class RecordingAdvisor implements BaseAdvisor {
        volatile boolean beforeInvoked;
        volatile boolean afterInvoked;
        volatile Integer promptTokens;
        volatile Integer completionTokens;

        @Override
        public String getName() {
            return "RecordingAdvisor";
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
            beforeInvoked = true;
            return request;
        }

        @Override
        public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
            afterInvoked = true;
            Usage usage = response.chatResponse().getMetadata().getUsage();
            promptTokens = usage.getPromptTokens();
            completionTokens = usage.getCompletionTokens();
            return response;
        }
    }

    /** @Tool 注解方法 bean（验 ToolCallback 注册路径）。 */
    static class Tools {
        @Tool(description = "返回当前时间")
        public String currentTime() {
            return "12:00";
        }
    }

    @Test
    @DisplayName("ChatClient + Advisor Chain + stub ChatModel：before/after 被调，usage 捕获，content 返回")
    void chatClientWithAdvisorChain() {
        RecordingAdvisor advisor = new RecordingAdvisor();
        ChatClient client = ChatClient.create(new StubChatModel());

        String content = client.prompt("hi")
                .advisors(advisor)
                .call()
                .content();

        assertThat(content).isEqualTo("smoke-ok");
        assertThat(advisor.beforeInvoked).isTrue();
        assertThat(advisor.afterInvoked).isTrue();
        assertThat(advisor.promptTokens).isEqualTo(10);
        assertThat(advisor.completionTokens).isEqualTo(20);
    }

    @Test
    @DisplayName("@Tool 注解方法经 MethodToolCallbackProvider 注册为 ToolCallback，name 正确")
    void toolRegistrationViaMethodToolCallbackProvider() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new Tools())
                .build()
                .getToolCallbacks();

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("currentTime");
    }

    @Test
    @DisplayName("ChatClient 注册 @Tool bean（.tools(...)）后调用仍跑通（Advisor + Tool 同链路）")
    void chatClientWithToolsAndAdvisor() {
        RecordingAdvisor advisor = new RecordingAdvisor();
        ChatClient client = ChatClient.create(new StubChatModel());

        String content = client.prompt("what time is it")
                .tools(new Tools())
                .advisors(advisor)
                .call()
                .content();

        assertThat(content).isEqualTo("smoke-ok");
        assertThat(advisor.afterInvoked).isTrue();
    }
}
