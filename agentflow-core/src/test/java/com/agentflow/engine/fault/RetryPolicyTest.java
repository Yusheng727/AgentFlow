package com.agentflow.engine.fault;

import com.agentflow.agent.AgentExecutionException;
import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.FatalException;
import com.agentflow.agent.TransientException;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.engine.NodeExecutor;
import com.agentflow.engine.NodeResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private static NodeDefinition node(String id, String agent) {
        return new NodeDefinition(id, agent, null, null, null, null, null, null);
    }

    private static AgentInput input(String nodeId) {
        return new AgentInput(nodeId, "a", null, new com.agentflow.engine.WorkflowContext(),
                java.util.Map.of(), java.util.List.of(), java.util.Map.of());
    }

    /** 造一个在前 failTimes 次抛 throwable、之后返 success 的 agent。 */
    private static AgentFunction flakyAgent(AtomicInteger calls, int failTimes, Throwable throwable) {
        return in -> {
            int n = calls.incrementAndGet();
            if (n <= failTimes) {
                if (throwable instanceof RuntimeException re) {
                    throw re;
                }
                if (throwable instanceof AgentExecutionException ae) {
                    throw ae;
                }
            }
            return AgentOutput.of("ok:" + n);
        };
    }

    private NodeExecutor executor(AgentFunction fn) {
        Function<String, AgentFunction> resolver = name -> fn;
        return new NodeExecutor(resolver, Executors.newVirtualThreadPerTaskExecutor());
    }

    @Test
    @DisplayName("transient 重试成功：前 2 次 TransientException，第 3 次成功（3 attempt）")
    void retryOnTransientThenSuccess() {
        AtomicInteger calls = new AtomicInteger();
        AgentFunction agent = flakyAgent(calls, 2, new TransientException("429"));
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(1), 2.0, ErrorClassifier.defaultClassifier());

        NodeResult r = policy.execute(executor(agent), node("A", "a"), input("A"));

        assertThat(r).isInstanceOf(NodeResult.Success.class);
        assertThat(((NodeResult.Success) r).output().content()).isEqualTo("ok:3");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("fatal 不重试：FatalException → 立即 Failure（1 attempt）")
    void noRetryOnFatal() {
        AtomicInteger calls = new AtomicInteger();
        AgentFunction agent = flakyAgent(calls, 99, new FatalException("400 bad request"));
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(1), 2.0, ErrorClassifier.defaultClassifier());

        NodeResult r = policy.execute(executor(agent), node("A", "a"), input("A"));

        assertThat(r).isInstanceOf(NodeResult.Failure.class);
        assertThat(((NodeResult.Failure) r).error()).isInstanceOf(FatalException.class);
        assertThat(calls.get()).isEqualTo(1);  // 不重试
    }

    @Test
    @DisplayName("重试耗尽：始终 TransientException → 3 attempt 后 Failure")
    void retryExhausted() {
        AtomicInteger calls = new AtomicInteger();
        AgentFunction agent = flakyAgent(calls, 99, new TransientException("always fail"));
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(1), 2.0, ErrorClassifier.defaultClassifier());

        NodeResult r = policy.execute(executor(agent), node("A", "a"), input("A"));

        assertThat(r).isInstanceOf(NodeResult.Failure.class);
        assertThat(((NodeResult.Failure) r).error()).isInstanceOf(TransientException.class);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("未知 RuntimeException 视为 fatal → 不重试（1 attempt）")
    void unknownRuntimeTreatedAsFatal() {
        AtomicInteger calls = new AtomicInteger();
        AgentFunction agent = flakyAgent(calls, 99, new IllegalStateException("unknown"));
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(1), 2.0, ErrorClassifier.defaultClassifier());

        NodeResult r = policy.execute(executor(agent), node("A", "a"), input("A"));

        assertThat(r).isInstanceOf(NodeResult.Failure.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("节点超时（NodeExecutor future.get 超时 → TimeoutException）视为 transient → 重试后成功")
    void timeoutIsRetried() {
        AtomicInteger calls = new AtomicInteger();
        // 第 1 次慢（触发节点超时），第 2 次立即返回
        AgentFunction agent = in -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return AgentOutput.of("ok:" + n);
        };
        NodeDefinition slowNode = new NodeDefinition("A", "a", null, null, "100ms", null, null, null);
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(1), 2.0, ErrorClassifier.defaultClassifier());

        NodeResult r = policy.execute(executor(agent), slowNode, input("A"));

        assertThat(r).isInstanceOf(NodeResult.Success.class);
        assertThat(((NodeResult.Success) r).output().content()).isEqualTo("ok:2");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("首次成功 → 1 attempt，不 sleep")
    void successFirstTry() {
        AtomicInteger calls = new AtomicInteger();
        AgentFunction agent = flakyAgent(calls, 0, new TransientException("never"));  // 0 fail
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(1), 2.0, ErrorClassifier.defaultClassifier());

        NodeResult r = policy.execute(executor(agent), node("A", "a"), input("A"));

        assertThat(r).isInstanceOf(NodeResult.Success.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("maxAttempts=1 → 不重试（即使 transient）")
    void noRetryWhenMaxAttemptsOne() {
        AtomicInteger calls = new AtomicInteger();
        AgentFunction agent = flakyAgent(calls, 99, new TransientException("fail"));
        RetryPolicy policy = new RetryPolicy(1, Duration.ofMillis(1), 2.0, ErrorClassifier.defaultClassifier());

        NodeResult r = policy.execute(executor(agent), node("A", "a"), input("A"));

        assertThat(r).isInstanceOf(NodeResult.Failure.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("构造校验：maxAttempts<1 / 负 backoff / multiplier<1 → IllegalArgumentException")
    void constructorValidation() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new RetryPolicy(0, Duration.ofSeconds(1), 2.0, ErrorClassifier.defaultClassifier()))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new RetryPolicy(3, Duration.ofSeconds(-1), 2.0, ErrorClassifier.defaultClassifier()))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new RetryPolicy(3, Duration.ofSeconds(1), 0.5, ErrorClassifier.defaultClassifier()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
