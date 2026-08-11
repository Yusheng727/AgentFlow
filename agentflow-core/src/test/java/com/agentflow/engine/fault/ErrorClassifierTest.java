package com.agentflow.engine.fault;

import com.agentflow.agent.AgentExecutionException;
import com.agentflow.agent.FatalException;
import com.agentflow.agent.TransientException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorClassifierTest {

    private final ErrorClassifier classifier = ErrorClassifier.defaultClassifier();

    @Test
    @DisplayName("TransientException → transient（可重试）")
    void transientExceptionIsTransient() {
        assertThat(classifier.isTransient(new TransientException("429"))).isTrue();
    }

    @Test
    @DisplayName("FatalException → fatal（不重试）")
    void fatalExceptionIsFatal() {
        assertThat(classifier.isTransient(new FatalException("400 bad request"))).isFalse();
    }

    @Test
    @DisplayName("TimeoutException → transient（节点超时可重试）")
    void timeoutIsTransient() {
        assertThat(classifier.isTransient(new TimeoutException())).isTrue();
    }

    @Test
    @DisplayName("IOException / SocketTimeoutException → transient（网络瞬时）")
    void ioExceptionIsTransient() {
        assertThat(classifier.isTransient(new IOException("connection reset"))).isTrue();
        assertThat(classifier.isTransient(new SocketTimeoutException("timeout"))).isTrue();
    }

    @Test
    @DisplayName("InterruptedException → transient")
    void interruptedIsTransient() {
        assertThat(classifier.isTransient(new InterruptedException())).isTrue();
    }

    @Test
    @DisplayName("AgentExecutionException 基类（未细分）→ fatal（保守不重试）")
    void baseAgentExecutionExceptionIsFatal() {
        assertThat(classifier.isTransient(new AgentExecutionException("unknown agent"))).isFalse();
    }

    @Test
    @DisplayName("未知 RuntimeException → fatal（保守不重试）")
    void unknownRuntimeIsFatal() {
        assertThat(classifier.isTransient(new RuntimeException("boom"))).isFalse();
        assertThat(classifier.isTransient(new IllegalStateException("state"))).isFalse();
    }

    @Test
    @DisplayName("null cause → fatal（不重试）")
    void nullCauseIsFatal() {
        assertThat(classifier.isTransient(null)).isFalse();
    }

    // ─────────────────── B2：composed 组合分类器（适配器注册框架规则，core 保持框架无关） ───────────────────

    /** 模拟框架 transient：某框架的 Retriable 类型（用自定义标记接口代替真实框架类，core 测试不引框架）。 */
    private static final class RetriableFrameworkException extends RuntimeException {
        RetriableFrameworkException() {
            super("framework retriable");
        }
    }

    private static final class FatalFrameworkException extends RuntimeException {
        FatalFrameworkException() {
            super("framework fatal");
        }
    }

    private final ErrorClassifier frameworkClassifier = cause ->
            cause instanceof RetriableFrameworkException;

    @Test
    @DisplayName("composed(fw)：框架分类器判 transient → transient")
    void composedFrameworkTransient() {
        ErrorClassifier c = ErrorClassifier.composed(frameworkClassifier);
        assertThat(c.isTransient(new RetriableFrameworkException())).isTrue();
    }

    @Test
    @DisplayName("composed(fw)：框架分类器判 fatal → fatal（且未被基础规则误判）")
    void composedFrameworkFatal() {
        ErrorClassifier c = ErrorClassifier.composed(frameworkClassifier);
        assertThat(c.isTransient(new FatalFrameworkException())).isFalse();
    }

    @Test
    @DisplayName("composed(fw)：框架判 false 但基础规则（IOException）判 transient → transient")
    void composedBaseRulesStillApply() {
        ErrorClassifier c = ErrorClassifier.composed(frameworkClassifier);
        assertThat(c.isTransient(new IOException("connection reset"))).isTrue();
    }

    @Test
    @DisplayName("composed() 无框架分类器 = 纯基础规则（不引任何框架知识）")
    void composedNoFrameworkIsBaseRules() {
        ErrorClassifier c = ErrorClassifier.composed();
        assertThat(c.isTransient(new IOException())).isTrue();
        assertThat(c.isTransient(new IllegalStateException())).isFalse();
    }
}
