package com.agentflow.agent;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Agent 注册表（U3）。
 *
 * <p>按 agent 名 → {@link AgentFunction} 查找。Spring 容器扫描 {@code AgentFunction} Bean 注册
 * （U3 的 {@code SpringAiAgentAdapter} 等）；YAML 节点的 {@code agent:} 字段通过本表解析到具体实现。
 *
 * <p>实现 {@link Function}<{@link String}, {@link AgentFunction}>，可直接作为
 * {@link com.agentflow.engine.NodeExecutor} / {@link com.agentflow.engine.BspEngine} 的 resolver
 * （最小改动接入，ce-code-review seam）。
 *
 * <p>解析语义：找不到已注册 agent → 抛 {@link IllegalStateException}（YAML 引用了未声明的 agent，
 * 属配置错误）。{@link com.agentflow.engine.NodeExecutor} 会把 resolver 抛出的异常包成
 * {@link NodeResult.Failure}，不破坏 no-throw 不变量。
 *
 * <p>注册语义：同名重复注册 → 抛 {@link IllegalStateException}（防止 Spring 容器内同名 Bean 静默覆盖，
 * 把配置冲突前置到启动期）。
 */
public final class NodeRegistry implements Function<String, AgentFunction> {

    private final Map<String, AgentFunction> agents = new ConcurrentHashMap<>();

    public NodeRegistry() {
    }

    public NodeRegistry(Map<String, AgentFunction> initial) {
        if (initial != null) {
            initial.forEach(this::register);
        }
    }

    /** 注册一个 agent。同名已存在 → 抛 IllegalStateException。 */
    public void register(String name, AgentFunction fn) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(fn, "fn");
        if (agents.putIfAbsent(name, fn) != null) {
            throw new IllegalStateException("Agent 已注册，重复注册: " + name);
        }
    }

    /** 批量注册。 */
    public void registerAll(Map<String, AgentFunction> more) {
        if (more != null) {
            more.forEach(this::register);
        }
    }

    /** 解析 agent 名 → AgentFunction。未注册 → 抛 IllegalStateException。 */
    public AgentFunction resolve(String name) {
        AgentFunction fn = agents.get(name);
        if (fn == null) {
            throw new IllegalStateException("未注册 agent: " + name);
        }
        return fn;
    }

    @Override
    public AgentFunction apply(String name) {
        return resolve(name);
    }

    /** 是否已注册。 */
    public boolean contains(String name) {
        return agents.containsKey(name);
    }

    /** 已注册 agent 数。 */
    public int size() {
        return agents.size();
    }
}
