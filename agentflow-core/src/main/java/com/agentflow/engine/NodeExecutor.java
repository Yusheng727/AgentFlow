package com.agentflow.engine;

import com.agentflow.agent.AgentExecutionException;
import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.dsl.NodeDefinition;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 单节点执行器：解析 AgentFunction + 超时 + cancel（KTD-6）。
 *
 * <p>把 {@code agent.execute(input)} 提交到 Virtual Thread executor，用 {@link Future#get(long, TimeUnit)}
 * 做节点级超时；超时触发 {@code future.cancel(true)} 中断在飞调用，并调
 * {@link AgentFunction#cancel(AgentInput)} 让适配器 abort 底层 HTTP（v4.3 真取消，停止 token 计费）。
 *
 * <p>任何异常包成 {@link NodeResult.Failure} 返回——不向上抛，保证同 super-step 其他节点不受影响
 * （BSP 异常隔离，U2 测试场景 6）。U4 的 RetryPolicy / ErrorClassifier 将在调用本类前后包裹重试与分类。
 *
 * <p>本类不持有 executor 生命周期——由 {@link BspEngine} 创建/关闭并注入，确保全工作流共享一个
 * Virtual Thread executor（U5 Semaphore 限流也加在引擎层）。
 */
public final class NodeExecutor {

    /** 节点级默认超时 120s（plan U4 TimeoutPolicy 约定）。 */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final Function<String, AgentFunction> agentResolver;
    private final ExecutorService executor;

    public NodeExecutor(Function<String, AgentFunction> agentResolver, ExecutorService executor) {
        this.agentResolver = agentResolver;
        this.executor = executor;
    }

    /**
     * 执行单节点（阻塞当前线程直到完成或超时）。并行由调用方在多个线程/Virtual Thread 上调用实现。
     */
    public NodeResult execute(NodeDefinition node, AgentInput input) {
        AgentFunction agent;
        try {
            agent = agentResolver.apply(node.agent());
        } catch (RuntimeException ex) {
            // resolver 抛异常不应破坏 no-throw 不变量（NodeExecutor 是 public API，接受任意 Function）
            return new NodeResult.Failure(node.id(),
                    new AgentExecutionException("agent 解析失败: " + node.agent(), ex));
        }
        if (agent == null) {
            return new NodeResult.Failure(node.id(),
                    new AgentExecutionException("未注册 agent: " + node.agent()));
        }

        Future<AgentOutput> future = executor.submit(() -> agent.execute(input));
        Duration timeout = parseTimeout(node.timeout());
        try {
            AgentOutput output = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (output == null) {
                // agent 契约要求返回非 null AgentOutput；null 视为失败，防 barrier 阶段 NPE
                cancelNode(future, agent, input);
                return new NodeResult.Failure(node.id(),
                        new AgentExecutionException("agent 返回 null output: " + node.agent()));
            }
            return new NodeResult.Success(node.id(), output);
        } catch (TimeoutException e) {
            cancelNode(future, agent, input);
            return new NodeResult.Failure(node.id(), e);
        } catch (ExecutionException e) {
            // agent.execute 抛出的异常被包成 ExecutionException，取 cause
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new NodeResult.Failure(node.id(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelNode(future, agent, input);
            return new NodeResult.Failure(node.id(), e);
        }
    }

    /**
     * 中断在飞调用 + 触发适配器级 cancel。cancel() 自身抛异常不向上传播——
     * 主失败（timeout/interrupt）已记录，secondary cancel 失败不可阻塞引擎（KTD-6 best-effort 止损）。
     */
    private void cancelNode(Future<?> future, AgentFunction agent, AgentInput input) {
        future.cancel(true);      // 中断 Virtual Thread（FutureTask.cancel(true) 真中断）
        try {
            agent.cancel(input);   // 适配器级 HTTP abort（KTD-6 v4.3）
        } catch (RuntimeException cancelEx) {
            // best-effort 止损，吞掉 secondary exception
        }
    }

    /**
     * 解析 NodeDefinition.timeout（"120s"/"2m"/"500ms"/"1h"），非法或缺失走默认 120s。
     * U4 的 TimeoutPolicy 会做更完整的策略化（节点级覆盖 + 工作流级总超时）。
     */
    static Duration parseTimeout(String spec) {
        if (spec == null || spec.isBlank()) {
            return DEFAULT_TIMEOUT;
        }
        String t = spec.trim().toLowerCase();
        Duration parsed;
        try {
            if (t.endsWith("ms")) {
                parsed = Duration.ofMillis(Long.parseLong(t.substring(0, t.length() - 2)));
            } else if (t.endsWith("s")) {
                parsed = Duration.ofSeconds(Long.parseLong(t.substring(0, t.length() - 1)));
            } else if (t.endsWith("m")) {
                parsed = Duration.ofMinutes(Long.parseLong(t.substring(0, t.length() - 1)));
            } else if (t.endsWith("h")) {
                parsed = Duration.ofHours(Long.parseLong(t.substring(0, t.length() - 1)));
            } else {
                // 裸数字 → 秒
                parsed = Duration.ofSeconds(Long.parseLong(t));
            }
        } catch (NumberFormatException e) {
            // 非法格式静默降级为默认——U1 SemanticValidator 后续可加 timeout 格式校验提前拦截
            return DEFAULT_TIMEOUT;
        }
        // 零或负超时会导致 Future.get 抛 IllegalArgumentException（崩溃）或立即 TimeoutException，
        // 一律降级为默认
        if (parsed.isZero() || parsed.isNegative()) {
            return DEFAULT_TIMEOUT;
        }
        return parsed;
    }
}
