package com.agentflow.demo.api;

import com.agentflow.adapters.langchain4j.LangChain4jAgentAdapter;
import com.agentflow.adapters.mock.MockAgentFunction;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.api.LocalVirtualThreadDispatcher;
import com.agentflow.api.WorkflowDispatcher;
import com.agentflow.api.WorkflowExecutionService;
import com.agentflow.api.security.ApiKeyAuthFilter;
import com.agentflow.api.security.CallerToolAllowlist;
import com.agentflow.api.security.WorkflowOwnershipChecker;
import com.agentflow.api.security.WorkflowSubmissionGuard;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.checkpoint.CheckpointManager;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.observability.AgentFlowMetrics;
import com.agentflow.observability.CostCalculator;
import com.agentflow.observability.ExecutionTraceRegistry;
import com.agentflow.prompt.OutputSchemaValidator;
import com.agentflow.version.InMemoryWorkflowDefinitionStore;
import com.agentflow.version.WorkflowDefinitionStore;
import com.agentflow.version.WorkflowVersionManager;

import dev.langchain4j.model.openai.OpenAiChatModel;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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

    private static final Logger log = LoggerFactory.getLogger(ApiConfig.class);

    @Bean
    public WorkflowDSLParser workflowDSLParser() {
        return new WorkflowDSLParser();
    }

    @Bean
    public ExecutionTraceRegistry executionTraceRegistry() {
        return new ExecutionTraceRegistry();
    }

    // MeterRegistry 不在此处定义：由 Spring Boot 自动配置（micrometer-registry-prometheus 在类路径 →
    // PrometheusMeterRegistry + /actuator/prometheus scrape endpoint）。agentFlowMetrics 注入该 registry，
    // U7 的 5 指标族即可被 Prometheus 抓取（Grafana 数据地基，见 docs/GRAFANA.md）。

    @Bean
    public AgentFlowMetrics agentFlowMetrics(MeterRegistry meterRegistry) {
        return new AgentFlowMetrics(meterRegistry);
    }

    /** 默认行为引擎 + trace + 指标：等价 {@code new BspEngine()} 但注入 traceRegistry + AgentFlowMetrics（U7 可观测入口）。 */
    @Bean
    public BspEngine bspEngine(ExecutionTraceRegistry executionTraceRegistry, AgentFlowMetrics agentFlowMetrics) {
        return new BspEngine(new DAGLayerer(), null, null, null, executionTraceRegistry, agentFlowMetrics);
    }

    @Bean
    public ChannelReducer channelReducer() {
        return new ChannelReducer();
    }

    @Bean
    public CheckpointManager checkpointManager() {
        return new InMemoryCheckpointManager();
    }

    /**
     * NodeRegistry：默认 fallback → MockAgentFunction（任意未注册 agent 名 → 零 LLM mock，向后兼容）。
     *
     * <p><b>真实 LLM 接线（档 1，C1/B1/B3 端到端生效）</b>：{@code agentflow.real.enabled=true} 且 env
     * {@code DEEPSEEK_API_KEY} 存在时，把指定 agent 名（{@code agentflow.real.agent}，默认 {@code deepseek}）
     * 注册到真实适配器（OpenAI 兼容 → DeepSeek），注入 metrics/model/schemaValidator 全配——YAML
     * {@code agent: deepseek} 的节点即走真实 LLM，其余 agent 名仍回落 mock。凭证只从 env 读，禁止写死。
     */
    @Bean
    public NodeRegistry nodeRegistry(
            AgentFlowMetrics agentFlowMetrics,
            @Value("${agentflow.mock.model:gpt-4o-mini}") String mockModel,
            @Value("${agentflow.mock.budget-threshold-usd:}") String budgetThresholdUsd,
            @Value("${agentflow.real.enabled:false}") boolean realEnabled,
            @Value("${agentflow.real.agent:deepseek}") String realAgentName,
            @Value("${agentflow.real.model:deepseek-chat}") String realModel,
            @Value("${agentflow.real.base-url:https://api.deepseek.com}") String realBaseUrl) {
        MockAgentFunction mock = new MockAgentFunction(agentFlowMetrics, mockModel, parseBudget(budgetThresholdUsd));
        NodeRegistry registry = new NodeRegistry(name -> mock);
        if (realEnabled) {
            registry.register(realAgentName, realDeepSeekAdapter(agentFlowMetrics, realAgentName, realModel, realBaseUrl));
        }
        return registry;
    }

    /** 构造真实 DeepSeek 适配器（OpenAI 兼容）。凭证从 env {@code DEEPSEEK_API_KEY} 读，缺则启动失败（不做静默回落）。 */
    private LangChain4jAgentAdapter realDeepSeekAdapter(
            AgentFlowMetrics metrics, String agentName, String model, String baseUrl) {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "agentflow.real.enabled=true 但缺 DEEPSEEK_API_KEY 环境变量：真实 LLM 路径需要凭证，禁止硬编码。"
                            + "请 export DEEPSEEK_API_KEY=... 后重启，或设 agentflow.real.enabled=false 退回 mock。");
        }
        OpenAiChatModel chat = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .build();
        LangChain4jAgentAdapter adapter = new LangChain4jAgentAdapter(
                chat, java.util.List.of(), null, Function.identity(),
                metrics, model, new OutputSchemaValidator());
        log.info("真实 LLM agent [{}] 已注册：model={} baseUrl={}（凭证来自 env DEEPSEEK_API_KEY）",
                agentName, model, baseUrl);
        return adapter;
    }

    /** 解析预算阈值：空串/非法 → null（不查预算）。 */
    private static Double parseBudget(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析整数配置：空/非法 → defaultValue（守卫默认启用节点数上界）。 */
    private static Integer parseIntOrNull(String s, Integer defaultValue) {
        if (s == null || s.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Bean
    public WorkflowOwnershipChecker workflowOwnershipChecker(CheckpointManager checkpointManager) {
        return new WorkflowOwnershipChecker(checkpointManager);
    }

    @Bean
    public CallerToolAllowlist callerToolAllowlist() {
        return new CallerToolAllowlist(Map.of());
    }

    // ─── 提交守卫（安全）：DAG 节点数 / 预估成本上界，超限 422 拒绝（防无界 VT + 烧成本） ───

    @Bean
    public CostCalculator costCalculator() {
        return new CostCalculator().loadFromClasspath("agentflow-cost-pricings.json");
    }

    /** 提交守卫 bean：max-nodes 默认启（DEFAULT_MAX_NODES），max-cost-usd 配了才启成本检查。 */
    @Bean
    public WorkflowSubmissionGuard workflowSubmissionGuard(
            CostCalculator costCalculator,
            @Value("${agentflow.guard.max-nodes:}") String maxNodesRaw,
            @Value("${agentflow.guard.max-cost-usd:}") String maxCostUsdRaw,
            @Value("${agentflow.mock.model:gpt-4o-mini}") String model) {
        Integer maxNodes = parseIntOrNull(maxNodesRaw, WorkflowSubmissionGuard.DEFAULT_MAX_NODES);
        Double maxCostUsd = parseBudget(maxCostUsdRaw);
        return new WorkflowSubmissionGuard(costCalculator, model, maxNodes, maxCostUsd);
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

    // ─── 异步派发（v1.1，KTD-8/KTD-B）：默认本地 VT 调度，kafka-starter 装配时经
    //      agentflow.kafka.enabled 门控替换为 Kafka 派发（KTD-C） ───

    @Bean
    public WorkflowExecutionService workflowExecutionService(
            BspEngine bspEngine, NodeRegistry nodeRegistry, CheckpointManager checkpointManager,
            WorkflowVersionManager workflowVersionManager) {
        return new WorkflowExecutionService(bspEngine, nodeRegistry, checkpointManager,
                new ChannelReducer(), workflowVersionManager);
    }

    @Bean
    @ConditionalOnProperty(name = "agentflow.kafka.enabled", havingValue = "false", matchIfMissing = true)
    public WorkflowDispatcher workflowDispatcher(WorkflowExecutionService workflowExecutionService) {
        return new LocalVirtualThreadDispatcher(workflowExecutionService);
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
