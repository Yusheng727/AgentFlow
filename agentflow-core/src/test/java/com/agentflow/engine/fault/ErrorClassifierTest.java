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

    @Test
    @DisplayName("composed 对 null cause → false（不 NPE，B2 review P3 兜底）")
    void composedNullCauseFalse() {
        ErrorClassifier c = ErrorClassifier.composed(frameworkClassifier);
        assertThat(c.isTransient(null)).isFalse();
    }

    // ─────────────────── toExecutionException（review B2 P1：沿 cause 兜底，保留框架标记在外层） ───────────────────

    @Test
    @DisplayName("toExecutionException：标记在外层（包装底层异常）→ 沿 cause 判 transient")
    void toExecutionClassifiesOuterMarker() {
        // 模拟 LC4j 真实形状：Retriable 标记在外层、内层是无标记异常（真实是 RateLimitException(httpException)）
        ErrorClassifier fw = ErrorClassifier.composed(cause -> cause instanceof RetriableFrameworkException);
        RetriableFrameworkException outer = new RetriableFrameworkException();
        outer.initCause(new IllegalStateException("inner"));
        var ex = ErrorClassifier.toExecutionException(fw, outer);
        assertThat(ex).isInstanceOf(TransientException.class);
    }

    @Test
    @DisplayName("toExecutionException：包装器 outer + 框架异常在 cause → 沿 cause 兜底判 transient")
    void toExecutionClassifiesInnerCause() {
        ErrorClassifier fw = ErrorClassifier.composed(cause -> cause instanceof RetriableFrameworkException);
        var ex = ErrorClassifier.toExecutionException(fw,
                new RuntimeException(new RetriableFrameworkException()));
        assertThat(ex).isInstanceOf(TransientException.class);
    }

    @Test
    @DisplayName("toExecutionException：非 transient → FatalException，消息取最深层非空")
    void toExecutionFatal() {
        ErrorClassifier fw = ErrorClassifier.composed(cause -> cause instanceof RetriableFrameworkException);
        var ex = ErrorClassifier.toExecutionException(fw,
                new RuntimeException(new IllegalStateException("deep reason")));
        assertThat(ex).isInstanceOf(FatalException.class);
        assertThat(ex.getMessage()).contains("deep reason"); // 最深层非空消息
    }
}
