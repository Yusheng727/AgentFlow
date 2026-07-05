package com.agentflow.engine.fault;

import com.agentflow.engine.WorkflowContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorHandlerTest {

    @Test
    @DisplayName("默认 handler：写 errorHandled=true + errorCause=<message>")
    void defaultHandlerWritesCompensation() {
        ErrorHandler handler = ErrorHandler.defaultHandler();
        WorkflowContext ctx = new WorkflowContext();

        handler.handle(ctx, new RuntimeException("boom"));

        assertThat(ctx.getValue("errorHandled")).isEqualTo(true);
        assertThat(ctx.getValue("errorCause")).isEqualTo("boom");
    }

    @Test
    @DisplayName("null cause → errorCause='unknown'")
    void nullCauseHandled() {
        ErrorHandler handler = ErrorHandler.defaultHandler();
        WorkflowContext ctx = new WorkflowContext();

        handler.handle(ctx, null);

        assertThat(ctx.getValue("errorHandled")).isEqualTo(true);
        assertThat(ctx.getValue("errorCause")).isEqualTo("unknown");
    }

    @Test
    @DisplayName("null context → 安全 no-op（不抛）")
    void nullContextSafe() {
        ErrorHandler handler = ErrorHandler.defaultHandler();
        handler.handle(null, new RuntimeException("x"));  // 不抛
    }

    @Test
    @DisplayName("自定义 handler：写任意补偿字段")
    void customHandler() {
        ErrorHandler handler = (ctx, cause) -> ctx.put("compensation", "fallback-value");
        WorkflowContext ctx = new WorkflowContext();

        handler.handle(ctx, new RuntimeException("fail"));

        assertThat(ctx.getValue("compensation")).isEqualTo("fallback-value");
    }
}
