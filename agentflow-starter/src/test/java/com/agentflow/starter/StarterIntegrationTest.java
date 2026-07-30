package com.agentflow.starter;

import com.agentflow.agent.AgentFunction;
import com.agentflow.adapters.mock.MockAgentFunction;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.engine.checkpoint.CheckpointManager;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.security.CredentialManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starter 集成测试（plan U13 Test scenarios：@EnableAgentFlow + mock profile 自动注入 + 跑通 Demo）。
 *
 * <p>验证：
 * <ul>
 *   <li>mock 模式（{@code agentflow.mock.enabled=true}）上下文加载成功，核心 Bean 全部注入</li>
 *   <li>注入的 Bean 能端到端跑通工作流（内联 YAML + MockAgentFunction + InMemoryCheckpointManager）</li>
 *   <li>默认模式（无 mock 配置）上下文加载成功</li>
 * </ul>
 */
class StarterIntegrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentFlowAutoConfiguration.class));

    @Test
    @DisplayName("mock 模式：所有核心 Bean 注入成功")
    void mockModeAllBeansRegistered() {
        runner.withPropertyValues("agentflow.mock.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(WorkflowDSLParser.class);
                    assertThat(context).hasSingleBean(BspEngine.class);
                    assertThat(context).hasSingleBean(ChannelReducer.class);
                    assertThat(context).hasSingleBean(CredentialManager.class);
                    assertThat(context).hasSingleBean(CheckpointManager.class);
                    assertThat(context.getBean(CheckpointManager.class))
                            .isInstanceOf(InMemoryCheckpointManager.class);
                    @SuppressWarnings("unchecked")
                    Function<String, AgentFunction> resolver = context.getBean(
                            "mockAgentResolver", Function.class);
                    assertThat(resolver.apply("any")).isInstanceOf(MockAgentFunction.class);
                });
    }

    @Test
    @DisplayName("mock 模式：注入的 Bean 能端到端跑通工作流")
    void mockModeEndToEndWorks() {
        runner.withPropertyValues("agentflow.mock.enabled=true")
                .run(context -> {
                    WorkflowDSLParser parser = context.getBean(WorkflowDSLParser.class);
                    BspEngine engine = context.getBean(BspEngine.class);
                    CheckpointManager cp = context.getBean(CheckpointManager.class);
                    @SuppressWarnings("unchecked")
                    Function<String, AgentFunction> resolver = context.getBean(
                            "mockAgentResolver", Function.class);

                    // 内联最小 YAML（2 节点串行，验证引擎链路）
                    String yaml = """
                            agentflow: { version: "1.0" }
                            nodes:
                              - { id: A, agent: a, mock_response: "a-out" }
                              - { id: B, agent: b, mock_response: "${A}" }
                            edges:
                              - { from: A, to: B }
                            """;
                    WorkflowDefinition def = parser.parse(
                            new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

                    Map<String, AgentFunction> agents = Map.of("a", resolver.apply("a"), "b", resolver.apply("b"));
                    WorkflowContext result = engine.execute(
                            def, agents, Map.of(), cp, new ChannelReducer(), "test-starter-1");

                    // B 的 mock_response "${A}" 被替换为 "a-out"（占位符 → 上下文传递验证）
                    assertThat(result.getValue("B")).asString().isEqualTo("a-out");
                });
    }

    @Test
    @DisplayName("默认模式（无 mock 配置）→ 上下文加载成功，CheckpointManager 未注册（缺 DataSource）")
    void defaultModeLoadsWithoutCheckpointManager() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(WorkflowDSLParser.class);
            assertThat(context).hasSingleBean(BspEngine.class);
            // 无 mock.enabled + 无 DataSource → PostgresCheckpointManager 条件不满足
            assertThat(context).doesNotHaveBean("postgresCheckpointManager");
            assertThat(context).doesNotHaveBean("inMemoryCheckpointManager");
        });
    }
}
