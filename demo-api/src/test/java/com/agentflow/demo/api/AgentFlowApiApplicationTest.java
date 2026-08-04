package com.agentflow.demo.api;

import com.agentflow.adapters.mock.MockAgentFunction;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.api.DiagnosisController;
import com.agentflow.api.TraceController;
import com.agentflow.api.WorkflowController;
import com.agentflow.api.security.ApiKeyAuthFilter;
import com.agentflow.api.security.CallerToolAllowlist;
import com.agentflow.api.security.WorkflowOwnershipChecker;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.observability.ExecutionTraceRegistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 可运行 REST API 端到端测试（后续任务 #9）。
 *
 * <p>{@link MockMvc} standalone：不改全局上下文，直接把 {@code ApiConfig} 的 bean（引擎带
 * ExecutionTraceRegistry + NodeRegistry mock fallback）+ 三个 Controller + {@link ApiKeyAuthFilter}
 * 手工装配，走真实 filter → controller → 引擎 → trace registry → Jackson 序列化链路——
 * 证明 UI「真实 API 优先」路径在 mock 模式真正可用（此前仓库无 wiring：TraceController 永远 404、
 * NodeRegistry 空导致提交执行失败）。
 *
 * <p>注：不依赖 {@code @SpringBootTest} + 嵌入式服务器——Boot 4.1 把 MockMvc/web 测试自动配置
 * 移出核心 test-autoconfigure，且 starter 带入的 spring-ai/JDBC 自动配置在无 key/无 driver 时
 * 会挡启动。standalone 直接验证 MVC 链路，等价且更快更确定。
 *
 * <p>覆盖：提交(202) → 轮询状态(SUCCESS) → 真实 trace(200 非空 + durationMs) → 鉴权 401/403 → 非法 YAML 400。
 */
class AgentFlowApiApplicationTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String DEMO_KEY = "demo-key-1234567890abcdef"; // UI 默认 key（api.ts）
    private static final String OTHER_KEY = "other-demo-key";           // 第二合法 key（403 所有权对照）

    private static final String YAML = """
            agentflow:
              version: "1.0"
            nodes:
              - id: greet
                agent: greeter
                prompt_template: "hi ${name}"
                mock_response: "hello world"
            edges: []
            """;

    @BeforeEach
    void setUp() {
        ExecutionTraceRegistry registry = new ExecutionTraceRegistry();
        InMemoryCheckpointManager cp = new InMemoryCheckpointManager();
        BspEngine engine = new BspEngine(new DAGLayerer(), null, null, null, registry);
        NodeRegistry nodeRegistry = new NodeRegistry(name -> new MockAgentFunction());
        WorkflowOwnershipChecker ownership = new WorkflowOwnershipChecker(cp);
        CallerToolAllowlist allowlist = new CallerToolAllowlist(Map.of());

        // 与生产一致：Boot web ObjectMapper 支持 JavaTimeModule（Snapshot 的 Instant 字段反序列化）
        ObjectMapper webMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

        mockMvc = standaloneSetup(
                new WorkflowController(new WorkflowDSLParser(), engine, cp, ownership, allowlist, nodeRegistry),
                new TraceController(registry, ownership),
                new DiagnosisController())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(webMapper))
                .addFilters(new ApiKeyAuthFilter(Set.of(DEMO_KEY, OTHER_KEY)))
                .build();
    }

    @Test
    @DisplayName("真实 API 路径：提交(202)→状态(SUCCESS)→trace(200 非空 + durationMs)")
    void realApiPathSubmitStatusTrace() throws Exception {
        String workflowId = submitAndGetId(DEMO_KEY, "greeting");
        assertThat(workflowId).isNotBlank();

        // 轮询状态到终态（mock 执行毫秒级）
        assertThat(pollStatus(workflowId, DEMO_KEY)).isEqualTo("SUCCESS");

        // 真实 trace：非空 + 关键 NodeTrace 字段（P1 修复：真实轨迹含 durationMs）
        String traceBody = getBody("/api/workflows/" + workflowId + "/trace", DEMO_KEY, 200);
        JsonNode trace = objectMapper.readTree(traceBody);
        assertThat(trace.path("nodes")).isNotEmpty();
        assertThat(trace.path("nodes").get(0).path("nodeId").asText()).isEqualTo("greet");
        assertThat(trace.path("nodes").get(0).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(trace.path("nodes").get(0).hasNonNull("durationMs")).isTrue();
    }

    @Test
    @DisplayName("鉴权：缺 Key → 401；非创建者合法 Key → 403（IDOR 防护）")
    void authAndOwnership() throws Exception {
        String workflowId = submitAndGetId(DEMO_KEY, "auth-wf");

        // 无 Key → 401
        assertThat(getStatus("/api/workflows/" + workflowId + "/trace", null)).isEqualTo(401);
        assertThat(getStatus("/api/workflows/" + workflowId + "/status", null)).isEqualTo(401);

        // 合法但非创建者 Key → 403（TraceController/WorkflowController 所有权校验）
        assertThat(getStatus("/api/workflows/" + workflowId + "/trace", OTHER_KEY)).isEqualTo(403);
        assertThat(getStatus("/api/workflows/" + workflowId + "/status", OTHER_KEY)).isEqualTo(403);
    }

    @Test
    @DisplayName("提交非法 YAML → 400（同步 parser 校验）")
    void submitInvalidYamlRejected() throws Exception {
        MvcResult resp = mockMvc.perform(post("/api/workflows")
                        .header("X-API-Key", DEMO_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workflowName", "bad",
                                "version", "1.0",
                                "yamlContent", "agentflow:",
                                "inputs", Map.of()))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(resp.getResponse().getContentAsString()).contains("INVALID_YAML");
    }

    @Test
    @DisplayName("#11 诊断 round-trip：真实 trace → POST /diagnosis → 200 报告（NodeTrace 反序列化还原结构字段）")
    void diagnosisRoundTrip() throws Exception {
        String workflowId = submitAndGetId(DEMO_KEY, "diag-wf");
        assertThat(pollStatus(workflowId, DEMO_KEY)).isEqualTo("SUCCESS");

        // GET 真实 trace，塞回诊断请求体（把 TraceController 返回的 Snapshot 原样回传）
        String traceBody = getBody("/api/workflows/" + workflowId + "/trace", DEMO_KEY, 200);
        JsonNode trace = objectMapper.readTree(traceBody);
        String diagBody = objectMapper.writeValueAsString(
                objectMapper.valueToTree(Map.of("workflowId", workflowId, "trace", trace)));

        MvcResult resp = mockMvc.perform(post("/api/diagnosis")
                        .header("X-API-Key", DEMO_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagBody))
                .andExpect(status().isOk())
                .andReturn();

        // 反序列化还原 NodeTrace 结构字段 → 真实诊断（此前 NodeTrace 无 @JsonCreator，节点为空/400 走 mock）
        JsonNode report = objectMapper.readTree(resp.getResponse().getContentAsString());
        assertThat(report.path("totalNodes").asInt()).isEqualTo(1);
        assertThat(report.path("failedNodes").asInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("#12 看板列表：GET /api/workflows 返回调用者工作流（按创建倒序 + 401 + 只见自己的）")
    void listWorkflowsEndpoint() throws Exception {
        String wfA = submitAndGetId(DEMO_KEY, "list-a");
        String wfB = submitAndGetId(DEMO_KEY, "list-b");
        submitAndGetId(OTHER_KEY, "list-other"); // OTHER 的，DEMO_KEY 看不到

        // 无 Key → 401
        assertThat(getStatus("/api/workflows", null)).isEqualTo(401);

        // DEMO_KEY 看到自己的 2 个（倒序：后提交的 b 在前），不含 OTHER 的
        String body = getBody("/api/workflows", DEMO_KEY, 200);
        JsonNode list = objectMapper.readTree(body);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).path("workflowId").asText()).isEqualTo(wfB);
        assertThat(list.get(1).path("workflowId").asText()).isEqualTo(wfA);
        assertThat(list.get(0).path("workflowName").asText()).isEqualTo("list-b");
        assertThat(list.get(0).path("status").asText()).isIn("PENDING", "RUNNING", "SUCCESS");
    }

    // ──────────────────── 辅助 ────────────────────

    private String submitAndGetId(String key, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/workflows")
                        .header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workflowName", name,
                                "version", "1.0",
                                "yamlContent", YAML,
                                "inputs", Map.of("name", "World")))))
                .andExpect(status().isAccepted())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("workflowId").asText();
    }

    private String getBody(String path, String key, int expectStatus) throws Exception {
        MvcResult r = mockMvc.perform(get(path).header("X-API-Key", key))
                .andExpect(status().is(expectStatus))
                .andReturn();
        return r.getResponse().getContentAsString();
    }

    private int getStatus(String path, String key) throws Exception {
        var builder = get(path);
        if (key != null) {
            builder = builder.header("X-API-Key", key);
        }
        return mockMvc.perform(builder).andReturn().getResponse().getStatus();
    }

    /** 轮询 GET /status 直到非 RUNNING 或超时（mock 毫秒级，上限 5s）。 */
    private String pollStatus(String workflowId, String key) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            MvcResult r = mockMvc.perform(get("/api/workflows/" + workflowId + "/status")
                            .header("X-API-Key", key))
                    .andExpect(status().isOk())
                    .andReturn();
            String status = objectMapper.readTree(r.getResponse().getContentAsString()).path("status").asText();
            if (!status.isEmpty() && !"RUNNING".equals(status)) {
                return status;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("轮询状态超时: " + workflowId);
    }
}
