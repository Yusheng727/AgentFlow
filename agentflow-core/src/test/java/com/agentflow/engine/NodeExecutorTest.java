package com.agentflow.engine;

import com.agentflow.agent.AgentExecutionException;
import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.dsl.NodeDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** NodeExecutor 单测：成功/失败/无 agent/超时 cancel/parseTimeout。 */
class NodeExecutorTest {

    private NodeDefinition node(String id, String agent, String timeout) {
        return new NodeDefinition(id, agent, null, null, timeout, null, null, null);
    }

    private AgentInput input(String nodeId) {
        return new AgentInput(nodeId, "a", null, new WorkflowContext(), java.util.Map.of());
    }

    // 成功
    @Test
    @DisplayName("执行成功 → NodeResult.Success")
    void success() {
        Function<String, AgentFunction> resolver = name -> in -> AgentOutput.of("ok:" + in.nodeId());
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            NodeExecutor ne = new NodeExecutor(resolver, exec);
            NodeResult r = ne.execute(node("A", "a", null), input("A"));
            assertThat(r).isInstanceOf(NodeResult.Success.class);
            assertThat(((NodeResult.Success) r).output().content()).isEqualTo("ok:A");
        }
    }

    // 异常 → Failure
    @Test
    @DisplayName("agent 抛异常 → NodeResult.Failure 保留 cause（异常隔离）")
    void failureWrapsCause() {
        Function<String, AgentFunction> resolver = name -> in -> {
            throw new AgentExecutionException("boom");
        };
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            NodeExecutor ne = new NodeExecutor(resolver, exec);
            NodeResult r = ne.execute(node("A", "a", null), input("A"));
            assertThat(r).isInstanceOf(NodeResult.Failure.class);
            assertThat(((NodeResult.Failure) r).error())
                    .isInstanceOf(AgentExecutionException.class)
                    .hasMessage("boom");
        }
    }

    // 无 agent
    @Test
    @DisplayName("未注册 agent → Failure 带 AgentExecutionException")
    void noAgentRegistered() {
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            NodeExecutor ne = new NodeExecutor(name -> null, exec);
            NodeResult r = ne.execute(node("A", "ghost", null), input("A"));
            assertThat(r).isInstanceOf(NodeResult.Failure.class);
            assertThat(((NodeResult.Failure) r).error())
                    .isInstanceOf(AgentExecutionException.class)
                    .hasMessageContaining("ghost");
        }
    }

    // 超时 + cancel
    @Test
    @DisplayName("超时 → Failure(TimeoutException) + cancel(true) 中断 + AgentFunction.cancel() 被调")
    void timeoutCancels() {
        AtomicBoolean cancelCalled = new AtomicBoolean();
        AgentFunction slow = new AgentFunction() {
            @Override
            public AgentOutput execute(AgentInput input) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return AgentOutput.of("late");
            }

            @Override
            public void cancel(AgentInput input) {
                cancelCalled.set(true);
            }
        };
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            NodeExecutor ne = new NodeExecutor(name -> slow, exec);
            NodeResult r = ne.execute(node("A", "a", "100ms"), input("A"));
            assertThat(r).isInstanceOf(NodeResult.Failure.class);
            assertThat(((NodeResult.Failure) r).error())
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
        }
        assertThat(cancelCalled.get()).isTrue();
    }

    // parseTimeout
    @Test
    @DisplayName("parseTimeout：s/m/ms/h/裸数字/null/blank/非法")
    void parseTimeoutVariants() {
        assertThat(NodeExecutor.parseTimeout(null)).isEqualTo(Duration.ofSeconds(120));
        assertThat(NodeExecutor.parseTimeout("  ")).isEqualTo(Duration.ofSeconds(120));
        assertThat(NodeExecutor.parseTimeout("120s")).isEqualTo(Duration.ofSeconds(120));
        assertThat(NodeExecutor.parseTimeout("2m")).isEqualTo(Duration.ofMinutes(2));
        assertThat(NodeExecutor.parseTimeout("500ms")).isEqualTo(Duration.ofMillis(500));
        assertThat(NodeExecutor.parseTimeout("1h")).isEqualTo(Duration.ofHours(1));
        assertThat(NodeExecutor.parseTimeout("45")).isEqualTo(Duration.ofSeconds(45));
        // 非法 → 默认
        assertThat(NodeExecutor.parseTimeout("abc")).isEqualTo(Duration.ofSeconds(120));
    }
}
