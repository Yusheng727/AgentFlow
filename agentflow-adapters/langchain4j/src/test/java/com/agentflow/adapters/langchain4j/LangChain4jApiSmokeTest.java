package com.agentflow.adapters.langchain4j;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KTD-7 冒烟测试（v1.1 前置 gate）：验证 LangChain4j 1.0.0 GA 的
 * ChatModel + ChatRequest（toolSpecifications）+ @Tool 注册 + TokenUsage API 表面，
 * 跑通才认定 LangChain4jAgentAdapter 可建。用 stub ChatModel 避免真实 API key
 * （与 SpringAiApiSmokeTest 同款模式）。
 */
class LangChain4jApiSmokeTest {

    /** stub ChatModel：返回固定响应 + TokenUsage(10,20)，验证 token 捕获链路。 */
    static class StubChatModel implements ChatModel {
        volatile ChatRequest lastRequest;

        @Override
        public ChatResponse chat(ChatRequest request) {
            this.lastRequest = request;
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("smoke-ok"))
                    .tokenUsage(new TokenUsage(10, 20))
                    .build();
        }
    }

    /** @Tool 注解方法 bean（验 ToolSpecification 注册路径）。 */
    static class Tools {
        @Tool("返回当前时间")
        String currentTime() {
            return "12:00";
        }
    }

    @Test
    @DisplayName("LangChain4j 1.0.0：chat(ChatRequest) 返回 content + TokenUsage（窄表面基础通路）")
    void chatReturnsContentAndUsage() {
        StubChatModel model = new StubChatModel();
        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(new dev.langchain4j.data.message.UserMessage("hi"))
                .build());

        assertThat(response.aiMessage().text()).isEqualTo("smoke-ok");
        assertThat(response.tokenUsage().inputTokenCount()).isEqualTo(10);
        assertThat(response.tokenUsage().outputTokenCount()).isEqualTo(20);
    }

    @Test
    @DisplayName("LangChain4j 1.0.0：toolSpecifications 随请求传递 + @Tool spec 注册（KTD-7 工具面）")
    void toolSpecificationsReachModel() {
        StubChatModel model = new StubChatModel();
        Tools tools = new Tools();
        var specs = dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom(tools);
        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).name()).isEqualTo("currentTime");

        model.chat(ChatRequest.builder()
                .messages(new dev.langchain4j.data.message.UserMessage("现在几点"))
                .toolSpecifications(specs)
                .build());

        // stub 收到的请求携带 toolSpecifications（不丢失）
        assertThat(model.lastRequest.toolSpecifications()).hasSize(1);
        assertThat(model.lastRequest.toolSpecifications().get(0).name()).isEqualTo("currentTime");
    }
}
