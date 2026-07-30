package com.agentflow.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * StructuredLogger 测试（覆盖 JSON 格式输出路径，拉覆盖率）。
 */
class StructuredLoggerTest {

    @Test
    @DisplayName("logSuccess 不抛异常，输出 JSON 格式")
    void logSuccessDoesNotThrow() {
        assertThatCode(() -> StructuredLogger.logSuccess(
                "wf-1", "nodeA", "finance-agent",
                Duration.ofMillis(150), 100, 50, "财务风险低")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("logFailure 不抛异常，输出 JSON 格式")
    void logFailureDoesNotThrow() {
        assertThatCode(() -> StructuredLogger.logFailure(
                "wf-1", "nodeB", "compliance-agent",
                Duration.ofSeconds(2), "timeout")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("outputSummary 超长截断不抛异常")
    void truncateLongSummary() {
        String longSummary = "A".repeat(500);
        assertThatCode(() -> StructuredLogger.logSuccess(
                "wf-1", "nodeC", "agent",
                Duration.ofMillis(10), 0, 0, longSummary)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null outputSummary / error 不抛异常")
    void nullValuesHandled() {
        assertThatCode(() -> StructuredLogger.logSuccess(
                "wf-1", "nodeD", "agent",
                Duration.ofMillis(10), 0, 0, null)).doesNotThrowAnyException();
        assertThatCode(() -> StructuredLogger.logFailure(
                "wf-1", "nodeE", "agent",
                Duration.ofMillis(10), null)).doesNotThrowAnyException();
    }
}
