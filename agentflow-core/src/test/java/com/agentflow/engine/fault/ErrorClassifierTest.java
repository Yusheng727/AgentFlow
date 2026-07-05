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
}
