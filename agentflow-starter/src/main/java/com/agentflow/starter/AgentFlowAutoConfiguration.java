package com.agentflow.starter;

import com.agentflow.agent.AgentFunction;
import com.agentflow.adapters.mock.MockAgentFunction;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.checkpoint.CheckpointManager;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.engine.checkpoint.PostgresCheckpointManager;
import com.agentflow.security.ColumnEncryptors;
import com.agentflow.security.CredentialManager;
import com.agentflow.agent.NodeRegistry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.Map;
import java.util.function.Function;

/**
 * AgentFlow 自动配置（U9 起引入，U13 完善全量核心 Bean 注册）。
 *
 * <h3>注册清单</h3>
 * <ul>
 *   <li><b>始终注册</b>：{@link WorkflowDSLParser} / {@link BspEngine} / {@link ChannelReducer} /
 *       {@link CredentialManager} / {@link NodeRegistry}（空默认，调用方填充 Agent）</li>
 *   <li><b>mock 模式</b>（{@code agentflow.mock.enabled=true}）：
 *       {@link InMemoryCheckpointManager}（零基础设施）+ {@code mockAgentResolver}（全部 Agent → MockAgentFunction）</li>
 *   <li><b>生产模式</b>（{@code agentflow.mock.enabled=false}，classpath 有 DataSource）：
 *       {@link PostgresCheckpointManager}（需 PG + Flyway）</li>
 * </ul>
 *
 * <h3>接入模式</h3>
 * <ol>
 *   <li><b>零基础设施（mock）</b>：{@code agentflow.mock.enabled=true}，只需 starter 依赖 + @EnableAgentFlow，
 *       BSP 引擎 + InMemory checkpoint + MockAgentFunction 全自动装配，可跑通 U10 Demo 等全量工作流</li>
 *   <li><b>生产部署</b>：{@code agentflow.mock.enabled=false} + 配 PG DataSource，
 *       {@link PostgresCheckpointManager} 自动注册（含 Flyway 迁移），REST API 端点就绪</li>
 *   <li><b>分布式</b>：自选 CheckpointManager 实现（如 Redis-backed），覆盖 starter 提供的 Bean</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(AgentFlowProperties.class)
public class AgentFlowAutoConfiguration {

    // ──────────────────────── 始终注册 ────────────────────────

    @Bean
    public WorkflowDSLParser workflowDSLParser() {
        return new WorkflowDSLParser();
    }

    @Bean
    public BspEngine bspEngine() {
        return new BspEngine();
    }

    @Bean
    public ChannelReducer channelReducer() {
        return new ChannelReducer();
    }

    @Bean
    public CredentialManager credentialManager(Environment env) {
        return new CredentialManager(env);
    }

    @Bean
    public NodeRegistry nodeRegistry() {
        return new NodeRegistry(Map.of());
    }

    // ──────────────────────── Mock 模式 ────────────────────────

    @Bean
    @ConditionalOnProperty(prefix = "agentflow.mock", name = "enabled", havingValue = "true")
    public CheckpointManager inMemoryCheckpointManager() {
        return new InMemoryCheckpointManager();
    }

    @Bean
    @ConditionalOnProperty(prefix = "agentflow.mock", name = "enabled", havingValue = "true")
    public Function<String, AgentFunction> mockAgentResolver() {
        MockAgentFunction mock = new MockAgentFunction();
        return name -> mock;
    }

    // ──────────────────────── 生产模式 ────────────────────────

    @Bean
    @ConditionalOnProperty(prefix = "agentflow.mock", name = "enabled", havingValue = "false",
            matchIfMissing = true)
    @ConditionalOnBean(DataSource.class)
    public CheckpointManager postgresCheckpointManager(DataSource dataSource) {
        // U7 R22：生产装配确定接入列级静态加密（fail-closed）——缺 AGENTFLOW_ENCRYPTION_KEY 抛错，拒绝明文落库
        return new PostgresCheckpointManager(dataSource, ColumnEncryptors.fromEnvStrict());
    }

    /**
     * U2 R22 扩列：生产模式注册 PG 定义存储，与 checkpoint 同一 strict 加密纪律
     * （{@link ColumnEncryptors#fromEnvStrict()}）——杜绝「checkpoint 加密了、定义还明文」的半吊子状态。
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentflow.mock", name = "enabled", havingValue = "false",
            matchIfMissing = true)
    @ConditionalOnBean(DataSource.class)
    public com.agentflow.version.PostgresWorkflowDefinitionStore postgresWorkflowDefinitionStore(DataSource dataSource) {
        return new com.agentflow.version.PostgresWorkflowDefinitionStore(dataSource, ColumnEncryptors.fromEnvStrict());
    }
}
