package com.agentflow.engine.fault;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.FatalException;
import com.agentflow.agent.TransientException;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.EdgeDefinition;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.engine.WorkflowExecutionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U4 容错集成测试（BspEngine 级，plan U4 6 Test scenarios）。
 *
 * <p>用 {@link BspEngine} 的 U4 构造注入 RetryPolicy / ErrorHandler / TimeoutPolicy，
 * 验证三层容错链路在真实 BSP 执行中的端到端行为。
 */
class FaultToleranceTest {

    private static NodeDefinition node(String id, String agent) {
        return new NodeDefinition(id, agent, null, null, null, null, null, null);
    }

    private static EdgeDefinition edge(String from, String to) {
        return new EdgeDefinition(from, to);
    }

    private static WorkflowDefinition wf(NodeDefinition... nodes) {
        return new WorkflowDefinition(null, null, List.of(nodes), List.of());
    }

    /** 单节点工作流（无 edges）。 */
    private static WorkflowDefinition singleNode(String id, String agent) {
        return wf(node(id, agent));
    }

    private static BspEngine engine(RetryPolicy retry, ErrorHandler handler, TimeoutPolicy timeout) {
        return new BspEngine(new DAGLayerer(), retry, handler, timeout);
    }

    private static BspEngine engineWithRetry(RetryPolicy retry, ErrorHandler handler) {
        return engine(retry, handler, new TimeoutPolicy());
    }

    @Test
    @DisplayName("场景 1：节点超时 → 重试 → 成功（BspEngine 级）")
    void nodeTimeoutRetriedThenSuccess() {
        // 节点 A 第 1 次慢（触发 100ms 节点超时），第 2 次快
        AtomicInteger calls = new AtomicInteger();
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
        WorkflowDefinition def = new WorkflowDefinition(null, null, List.of(slowNode), List.of());
        RetryPolicy retry = new RetryPolicy(3, Duration.ofMillis(1), 2.0, ErrorClassifier.defaultClassifier());
        BspEngine engine = engineWithRetry(retry, ErrorHandler.defaultHandler());

        WorkflowContext result = engine.execute(def, Map.of("a", agent), Map.of());

        assertThat(result.getValue("A")).isEqualTo("ok:2");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("场景 2：TransientException → 指数退避重试 → 成功")
    void transientRetriedThenSuccess() {
        AtomicInteger calls = new AtomicInteger();
        AgentFunction agent = in -> {
            int n = calls.incrementAndGet();
            if (n <= 2) {
                throw new TransientException("429 rate limit");
            }
            return AgentOutput.of("recovered");
        };
        RetryPolicy retry = new RetryPolicy(3, Duration.ofMillis(1), 2.0, ErrorClassifier.defaultClassifier());
        BspEngine engine = engineWithRetry(retry, ErrorHandler.defaultHandler());

        WorkflowContext result = engine.execute(singleNode("A", "a"), Map.of("a", agent), Map.of());

        assertThat(result.getValue("A")).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("场景 3：FatalException → 不重试 → 立即 ErrorHandler + 工作流 FAILED")
    void fatalImmediatelyFailedAndErrorHandlerCalled() {
        AtomicInteger calls = new AtomicInteger();
        AgentFunction agent = in -> {
            calls.incrementAndGet();
            throw new FatalException("400 bad request");
        };
        AtomicReference<Throwable> handled = new AtomicReference<>();
        ErrorHandler handler = (ctx, cause) -> handled.set(cause);
        RetryPolicy retry = new RetryPolicy(3, Duration.ofMillis(1), 2.0, ErrorClassifier.defaultClassifier());
        BspEngine engine = engineWithRetry(retry, handler);

        assertThatThrownBy(() -> engine.execute(singleNode("A", "a"), Map.of("a", agent), Map.of()))
                .isInstanceOf(WorkflowExecutionException.class)
                .satisfies(ex -> {
                    List<Throwable> failures = ((WorkflowExecutionException) ex).failures();
                    assertThat(failures).hasSize(1);
                    assertThat(failures.get(0)).isInstanceOf(FatalException.class);
                });
        assertThat(calls.get()).isEqualTo(1);  // fatal 不重试
        assertThat(handled.get()).isInstanceOf(FatalException.class);
    }

    @Test
    @DisplayName("场景 4：重试 3 次耗尽 → ErrorHandler 写 context errorHandled=true + 工作流 FAILED")
    void retryExhaustedTriggersErrorHandlerAndCompensation() {
        AtomicInteger calls = new AtomicInteger();
        AgentFunction agent = in -> {
            calls.incrementAndGet();
            throw new TransientException("always fail");
        };
        // 用 capturing handler 验证被调用 + cause；default handler 的 errorHandled=true 写入 engine 全局
        // context（failure 时不可访问），其行为在 ErrorHandlerTest 单测覆盖
        AtomicReference<Throwable> handled = new AtomicReference<>();
        ErrorHandler handler = (ctx, cause) -> {
            handled.set(cause);
            // 同时验证 handler 收到的是可写 context（能 put）
            ctx.put("errorHandled", true);
        };
        RetryPolicy retry = new RetryPolicy(3, Duration.ofMillis(1), 2.0, ErrorClassifier.defaultClassifier());
        BspEngine engine = engineWithRetry(retry, handler);

        assertThatThrownBy(() -> engine.execute(singleNode("A", "a"), Map.of("a", agent), Map.of()))
                .isInstanceOf(WorkflowExecutionException.class);
        assertThat(calls.get()).isEqualTo(3);  // 重试耗尽
        assertThat(handled.get()).isInstanceOf(TransientException.class);
    }

    @Test
    @DisplayName("场景 5：工作流级总超时 → abort，抛 WorkflowExecutionException(TimeoutException)")
    void workflowTotalTimeoutAborts() {
        AgentFunction slowAgent = in -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentOutput.of("slow");
        };
        // 工作流总超时 200ms（节点级默认 120s，故工作流总超时先触发）
        TimeoutPolicy timeout = new TimeoutPolicy(Duration.ofSeconds(120), Duration.ofMillis(200));
        BspEngine engine = engine(null, null, timeout);  // 无 retry/errorHandler，只测总超时

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> engine.execute(singleNode("A", "a"), Map.of("a", slowAgent), Map.of()))
                .isInstanceOf(WorkflowExecutionException.class)
                .satisfies(ex -> {
                    List<Throwable> failures = ((WorkflowExecutionException) ex).failures();
                    assertThat(failures).hasSize(1);
                    assertThat(failures.get(0)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                });
        long elapsed = System.currentTimeMillis() - start;
        // 应在 ~200ms 内 abort，远小于 5s
        assertThat(elapsed).isLessThan(2000);
    }

    @Test
    @DisplayName("场景 6：null ErrorHandler / null RetryPolicy → backward compat（U2 行为：不重试、不补偿）")
    void nullPolicyIsBackwardCompat() {
        // 无 retry：Transient 立即失败（不重试）
        AtomicInteger calls = new AtomicInteger();
        AgentFunction agent = in -> {
            calls.incrementAndGet();
            throw new TransientException("fail");
        };
        BspEngine engine = new BspEngine();  // 默认构造：retry/errorHandler/timeout 全 null

        assertThatThrownBy(() -> engine.execute(singleNode("A", "a"), Map.of("a", agent), Map.of()))
                .isInstanceOf(WorkflowExecutionException.class);
        assertThat(calls.get()).isEqualTo(1);  // 不重试
    }

    @Test
    @DisplayName("场景 7（额外）：成功工作流不调 ErrorHandler")
    void successDoesNotInvokeErrorHandler() {
        AtomicInteger handled = new AtomicInteger();
        ErrorHandler handler = (ctx, cause) -> handled.incrementAndGet();
        RetryPolicy retry = new RetryPolicy(3, Duration.ofMillis(1), 2.0, ErrorClassifier.defaultClassifier());
        BspEngine engine = engineWithRetry(retry, handler);
        AgentFunction agent = in -> AgentOutput.of("ok");

        WorkflowContext result = engine.execute(singleNode("A", "a"), Map.of("a", agent), Map.of());

        assertThat(result.getValue("A")).isEqualTo("ok");
        assertThat(handled.get()).isEqualTo(0);
    }
}
