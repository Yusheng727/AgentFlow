package com.agentflow.engine.fault;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeoutPolicyTest {

    @Test
    @DisplayName("默认：nodeDefault=120s，workflowTotal=null")
    void defaults() {
        TimeoutPolicy p = new TimeoutPolicy();
        assertThat(p.nodeDefault()).isEqualTo(Duration.ofSeconds(120));
        assertThat(p.workflowTotal()).isNull();
        assertThat(p.isWorkflowExceeded(Instant.now())).isFalse();
        assertThat(p.remainingWorkflow(Instant.now())).isNull();
    }

    @Test
    @DisplayName("workflowTotal 超时检测：start 在 5s 前 + total=2s → 已超时")
    void workflowExceeded() {
        TimeoutPolicy p = new TimeoutPolicy(Duration.ofSeconds(120), Duration.ofSeconds(2));
        Instant start = Instant.now().minus(5, ChronoUnit.SECONDS);
        assertThat(p.isWorkflowExceeded(start)).isTrue();
        assertThat(p.remainingWorkflow(start)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("workflowTotal 未超时：剩余为正")
    void workflowNotExceeded() {
        TimeoutPolicy p = new TimeoutPolicy(Duration.ofSeconds(120), Duration.ofSeconds(10));
        Instant start = Instant.now().minus(1, ChronoUnit.SECONDS);
        assertThat(p.isWorkflowExceeded(start)).isFalse();
        Duration rem = p.remainingWorkflow(start);
        assertThat(rem).isNotNull();
        assertThat(rem).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("workflowTotal=null → 永不超时（即使 start 很久前）")
    void nullTotalNeverExceeded() {
        TimeoutPolicy p = new TimeoutPolicy();
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        assertThat(p.isWorkflowExceeded(start)).isFalse();
    }

    @Test
    @DisplayName("构造校验：零/负 nodeDefault / 零/负 workflowTotal → IllegalArgumentException")
    void constructorValidation() {
        assertThatThrownBy(() -> new TimeoutPolicy(Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeoutPolicy(Duration.ofSeconds(-1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeoutPolicy(Duration.ofSeconds(120), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeoutPolicy(Duration.ofSeconds(120), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
