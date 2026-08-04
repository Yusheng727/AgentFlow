package com.agentflow.demo.api;

import com.agentflow.adapters.mock.MockAgentFunction;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.api.security.ApiKeyAuthFilter;
import com.agentflow.api.security.CallerToolAllowlist;
import com.agentflow.api.security.WorkflowOwnershipChecker;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.checkpoint.CheckpointManager;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.observability.AgentFlowMetrics;
import com.agentflow.observability.ExecutionTraceRegistry;
import com.agentflow.version.InMemoryWorkflowDefinitionStore;
import com.agentflow.version.WorkflowDefinitionStore;
import com.agentflow.version.WorkflowVersionManager;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * REST API wiring（后续任务 #9，可运行 server）。
 *
 * <p>显式声明核心 Bean（不依赖 {@code @EnableAgentFlow} 的裸引擎/空 NodeRegistry），要点：
 * <ul>
 *   <li><b>BspEngine 带 ExecutionTraceRegistry</b>——否则 execute() 不写 trace，
 *       TraceController 永远 404（此前仓库无 wiring，真实 API 路径不可用）</li>
 *   <li><b>NodeRegistry fallback → MockAgentFunction</b>——mock 模式任意 YAML agent 名
 *       都解析到零 LLM 实例（NodeRegistry 原本未知名抛错，见 U2 seam）</li>
 *   <li><b>ApiKeyAuthFilter 含 UI 默认 demo key</b>（与 {@code api.ts} 一致），另可经
 *       {@code agentflow.api.api-keys}（env 优先）追加合法 key</li>
 * </ul>
 */
@Configuration
public class ApiConfig {

    @Bean
    public WorkflowDSLParser workflowDSLParser() {
        return new WorkflowDSLParser();
    }

    @Bean
    public ExecutionTraceRegistry executionTraceRegistry() {
        return new ExecutionTraceRegistry();
    }

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    public AgentFlowMetrics agentFlowMetrics(MeterRegistry meterRegistry) {
        return new AgentFlowMetrics(meterRegistry);
    }

    /** 默认行为引擎 + trace：等价 {@code new BspEngine()} 但注入 traceRegistry（U7 可观测入口）。 */
    @Bean
    public BspEngine bspEngine(ExecutionTraceRegistry executionTraceRegistry) {
        return new BspEngine(new DAGLayerer(), null, null, null, executionTraceRegistry);
    }

    @Bean
    public ChannelReducer channelReducer() {
        return new ChannelReducer();
    }

    @Bean
    public CheckpointManager checkpointManager() {
        return new InMemoryCheckpointManager();
    }

    /** mock 模式：NodeRegistry fallback → 任意 agent 名 → MockAgentFunction（共享无状态单例，零 LLM）。 */
    @Bean
    public NodeRegistry nodeRegistry() {
        MockAgentFunction mock = new MockAgentFunction();
        return new NodeRegistry(name -> mock);
    }

    @Bean
    public WorkflowOwnershipChecker workflowOwnershipChecker(CheckpointManager checkpointManager) {
        return new WorkflowOwnershipChecker(checkpointManager);
    }

    @Bean
    public CallerToolAllowlist callerToolAllowlist() {
        return new CallerToolAllowlist(Map.of());
    }

    // ─── U8 版本管理：内存定义存储（mock/demo） + 版本管理器（WorkflowController 注入） ───

    @Bean
    public WorkflowDefinitionStore workflowDefinitionStore() {
        return new InMemoryWorkflowDefinitionStore();
    }

    @Bean
    public WorkflowVersionManager workflowVersionManager(WorkflowDefinitionStore workflowDefinitionStore) {
        return new WorkflowVersionManager(workflowDefinitionStore);
    }

    /** 鉴权过滤：{@code /api/*} 需 {@code X-API-Key}（含 UI 默认 demo key，另有 env {@code AGENTFLOW_API_KEYS} 追加）。 */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(
            @Value("${agentflow.api.api-keys:}") String apiKeysCsv) {
        Set<String> keys = new LinkedHashSet<>();
        // 与 agentflow-ui/src/lib/api.ts 默认 key 一致，保证 UI「真实 API 优先」开箱可用
        keys.add("demo-key-1234567890abcdef");
        if (apiKeysCsv != null && !apiKeysCsv.isBlank()) {
            for (String k : apiKeysCsv.split(",")) {
                if (!k.isBlank()) {
                    keys.add(k.trim());
                }
            }
        }
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiKeyAuthFilter(keys));
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
