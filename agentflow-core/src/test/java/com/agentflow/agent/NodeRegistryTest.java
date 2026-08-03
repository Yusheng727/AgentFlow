package com.agentflow.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeRegistryTest {

    private static AgentFunction echo() {
        return input -> AgentOutput.of("echo:" + input.nodeId());
    }

    @Test
    @DisplayName("register + resolve 正确返回 AgentFunction")
    void registerAndResolve() {
        NodeRegistry registry = new NodeRegistry();
        registry.register("finance-agent", echo());

        AgentFunction resolved = registry.resolve("finance-agent");
        assertThat(resolved).isSameAs(registry.resolve("finance-agent"));
        assertThat(registry.contains("finance-agent")).isTrue();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("resolve 未注册 agent → IllegalStateException")
    void resolveUnregistered() {
        NodeRegistry registry = new NodeRegistry();
        assertThatThrownBy(() -> registry.resolve("nope"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未注册 agent")
                .hasMessageContaining("nope");
    }

    @Test
    @DisplayName("apply (Function) 与 resolve 一致")
    void applyMatchesResolve() {
        NodeRegistry registry = new NodeRegistry();
        registry.register("a", echo());
        assertThat(registry.apply("a")).isSameAs(registry.resolve("a"));
    }

    @Test
    @DisplayName("重复注册同名 agent → IllegalStateException（防止静默覆盖）")
    void duplicateRegisterThrows() {
        NodeRegistry registry = new NodeRegistry();
        registry.register("a", echo());
        assertThatThrownBy(() -> registry.register("a", echo()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Agent 已注册")
                .hasMessageContaining("a");
    }

    @Test
    @DisplayName("registerAll 批量注册")
    void registerAll() {
        NodeRegistry registry = new NodeRegistry();
        registry.registerAll(Map.of("a", echo(), "b", echo()));
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.contains("a")).isTrue();
        assertThat(registry.contains("b")).isTrue();
    }

    @Test
    @DisplayName("Map 构造器初始化")
    void constructFromMap() {
        NodeRegistry registry = new NodeRegistry(Map.of("a", echo()));
        assertThat(registry.contains("a")).isTrue();
    }

    @Test
    @DisplayName("fallback 解析：注册表命中优先，未知名委托 fallback（mock 模式任意 agent 名）")
    void fallbackResolvesUnknownNames() {
        AgentFunction fb = echo();
        NodeRegistry registry = new NodeRegistry(name -> fb);
        // 未知名 → fallback
        assertThat(registry.resolve("arbitrary-agent")).isSameAs(fb);
        assertThat(registry.apply("any-name")).isSameAs(fb);
        // 注册表命中优先于 fallback
        AgentFunction known = echo();
        registry.register("known", known);
        assertThat(registry.resolve("known")).isSameAs(known);
    }

    @Test
    @DisplayName("fallback 返回 null → NPE（显式失败，不静默）")
    void fallbackReturningNullThrows() {
        NodeRegistry registry = new NodeRegistry(name -> null);
        assertThatThrownBy(() -> registry.resolve("x"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("register null name/fn → NPE")
    void registerNullArgs() {
        NodeRegistry registry = new NodeRegistry();
        assertThatThrownBy(() -> registry.register(null, echo()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.register("a", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("BspEngine 接 NodeRegistry resolver 跑通串行 DAG")
    void registryAsBspEngineResolver() {
        com.agentflow.dsl.WorkflowDefinition def = new com.agentflow.dsl.WorkflowDefinition(
                null, null,
                java.util.List.of(
                        new com.agentflow.dsl.NodeDefinition("A", "a", null, null, null, null, null, null),
                        new com.agentflow.dsl.NodeDefinition("B", "b", null, null, null, null, null, null),
                        new com.agentflow.dsl.NodeDefinition("C", "c", null, null, null, null, null, null)),
                java.util.List.of(
                        new com.agentflow.dsl.EdgeDefinition("A", "B"),
                        new com.agentflow.dsl.EdgeDefinition("B", "C")));
        NodeRegistry registry = new NodeRegistry();
        registry.register("a", input -> AgentOutput.of("a-out"));
        registry.register("b", input -> AgentOutput.of("b-out"));
        registry.register("c", input -> AgentOutput.of("c-out"));

        com.agentflow.engine.BspEngine engine = new com.agentflow.engine.BspEngine();
        com.agentflow.engine.WorkflowContext result = engine.execute(def, registry, java.util.Map.of());
        assertThat(result.getValue("A")).isEqualTo("a-out");
        assertThat(result.getValue("B")).isEqualTo("b-out");
        assertThat(result.getValue("C")).isEqualTo("c-out");
    }
}
