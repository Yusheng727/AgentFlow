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
import static org.assertj.core.api.Assertions.assertThatCode;

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
            // U2 R22 扩列：PG 定义存储同样条件注册，缺 DataSource 不注册
            assertThat(context).doesNotHaveBean("postgresWorkflowDefinitionStore");
        });
    }

    @Test
    @DisplayName("mock 模式：PG 定义存储不注册（仅生产模式）")
    void mockModeDoesNotRegisterPostgresDefinitionStore() {
        runner.withPropertyValues("agentflow.mock.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean("postgresWorkflowDefinitionStore"));
    }

    @Test
    @DisplayName("生产模式 + DataSource + 配 key：定义存储可完整实例化且注入 strict；checkpoint 需真库（mock DS 抛连库错）")
    void productionModeWithKeyRegistersBothEncryptedStores() {
        // env AGENTFLOW_ENCRYPTION_KEY 由 surefire environmentVariables 注入（32B base64 key）。
        // 说明：postgresWorkflowDefinitionStore 构造不触 Flyway，可完整实例化断言类型；
        // postgresCheckpointManager 构造器跑 Flyway 连 mock DataSource 必然失败（无真库）。
        // 原测试用 catch-all 吞异常 + assertThatCode(...).doesNotThrowAnyException() 恒绿（review P2）——
        // 即便 strict「缺 key 抛错」也照样绿，测不出它声称验证的 fail-closed 构造。改为：
        // 定义存储真断言（strict 注入经构造签名 + ColumnEncryptorsTest build seam 覆盖），
        // checkpoint 断言「mock DS 下确实抛连库错」（证明构造走真库路径，非空断言）。
        AgentFlowAutoConfiguration config = new AgentFlowAutoConfiguration();
        javax.sql.DataSource ds = org.mockito.Mockito.mock(javax.sql.DataSource.class);

        // ① 定义存储：mock DataSource 下完整实例化成功——strict 加密器注入 + 类型断言
        Object store = config.postgresWorkflowDefinitionStore(ds);
        assertThat(store).isInstanceOf(com.agentflow.version.PostgresWorkflowDefinitionStore.class);

        // ② checkpoint manager：mock DataSource 无真库 → Flyway 连接失败抛错（非恒绿吞掉）
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        config.postgresCheckpointManager(ds))
                .isInstanceOf(RuntimeException.class);
    }
}
