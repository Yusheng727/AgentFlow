package com.agentflow.starter;

import com.agentflow.agent.AgentFunction;
import com.agentflow.adapters.mock.MockAgentFunction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mock 模式 AutoConfiguration 集成测试（plan U9）。
 *
 * <p>验证 plan 场景「mock 与真实模式切换：同一 YAML 在不同环境跑通」的配置切换语义：
 * <ul>
 *   <li>{@code agentflow.mock.enabled=true} → mockAgentResolver Bean 注册，返回 MockAgentFunction</li>
 *   <li>{@code agentflow.mock.enabled=false}（默认）→ 不注册 mockAgentResolver Bean</li>
 * </ul>
 *
 * <p>完整端到端 BSP 跑通（parser + registry + engine 全套）留给 U13 Starter 完整封装时验证。
 * 本测试聚焦 AutoConfiguration 的 Bean 切换契约。
 */
class MockModeTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentFlowAutoConfiguration.class));

    @Test
    @DisplayName("agentflow.mock.enabled=true → mockAgentResolver Bean 注册，返回 MockAgentFunction")
    void mockEnabledRegistersMockResolver() {
        runner.withPropertyValues("agentflow.mock.enabled=true")
                .run(context -> {
                    @SuppressWarnings("unchecked")
                    Function<String, AgentFunction> resolver = context.getBean(
                            "mockAgentResolver", Function.class);
                    assertThat(resolver).isNotNull();
                    // 任意 name → MockAgentFunction 单例
                    AgentFunction agent = resolver.apply("any-agent");
                    assertThat(agent).isInstanceOf(MockAgentFunction.class);
                    // 同 name 再调 → 同实例（单例）
                    assertThat(resolver.apply("any-agent")).isSameAs(agent);
                });
    }

    @Test
    @DisplayName("agentflow.mock.enabled 缺失（默认 false）→ 不注册 mockAgentResolver")
    void mockDisabledDoesNotRegisterResolver() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean("mockAgentResolver");
        });
    }

    @Test
    @DisplayName("agentflow.mock.enabled=false → 不注册 mockAgentResolver")
    void mockExplicitlyDisabled() {
        runner.withPropertyValues("agentflow.mock.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("mockAgentResolver");
                });
    }

    @Configuration
    static class TestConfig {
        // 占位配置（ApplicationContextRunner 无需显式 @SpringBootConfiguration 也能加载 AutoConfiguration）
    }
}
