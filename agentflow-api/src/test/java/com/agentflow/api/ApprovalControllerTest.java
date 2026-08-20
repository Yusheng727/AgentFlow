package com.agentflow.api;

import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.api.security.ApiKeyAuthFilter;
import com.agentflow.api.security.WorkflowOwnershipChecker;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.ApprovalGateAgent;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.checkpoint.ApprovalRequest;
import com.agentflow.engine.checkpoint.ApprovalStatus;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.engine.checkpoint.WorkflowStatus;
import com.agentflow.version.InMemoryWorkflowDefinitionStore;
import com.agentflow.version.WorkflowVersionManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U6 审批 REST 端点测试：待批列表投影（不下发敏感载荷）、决策 APPROVE/REJECT 续跑、
 * decidedBy 服务端推导、越权 403、approval 归属校验。
 *
 * <p>standalone + {@link ApiKeyAuthFilter} 真过滤链：创建者可查/批；非创建者非 admin → 403；
 * 请求体带 {@code decidedBy} → 被忽略（审批记录 decidedBy = caller hash，防伪造）。
 */
class ApprovalControllerTest {

    private static final String OWNER_KEY = "owner-key-111";
    private static final String INTRUDER_KEY = "intruder-key-999";
    private static final String ADMIN_KEY = "admin-secret-777";
    private static final String OWNER_HASH = ApiKeyAuthFilter.sha256(OWNER_KEY);
    private static final String INTRUDER_HASH = ApiKeyAuthFilter.sha256(INTRUDER_KEY);
    private static final String ADMIN_HASH = ApiKeyAuthFilter.sha256(ADMIN_KEY);
    private static final String APPROVAL_YAML = """
            agentflow:
              version: "1.0"
            nodes:
              - { id: gate, agent: approval }
              - { id: finalize, agent: echo, mock_response: "done" }
            edges:
              - { from: gate, to: finalize }
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private InMemoryCheckpointManager cm;
    private WorkflowVersionManager versionManager;
    private WorkflowExecutionService svc;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        cm = new InMemoryCheckpointManager();
        versionManager = new WorkflowVersionManager(new InMemoryWorkflowDefinitionStore());
        versionManager.recordWorkflowDefinition("hitl", new WorkflowDSLParser().parse(
                new java.io.ByteArrayInputStream(APPROVAL_YAML.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        NodeRegistry registry = new NodeRegistry(Map.of("approval", new ApprovalGateAgent(),
                "echo", input -> AgentOutput.of(input.mockResponse())));
        svc = new WorkflowExecutionService(new BspEngine(), registry, cm, new ChannelReducer(), versionManager);
        ApprovalController controller = new ApprovalController(svc, cm,
                new WorkflowOwnershipChecker(cm), ADMIN_KEY);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new ApiKeyAuthFilter(Set.of(OWNER_KEY, INTRUDER_KEY, ADMIN_KEY)))
                .build();
    }

    /** 创建归属 OWNER_HASH 的工作流 + 触发审批暂停 + 返回 (workflowId, approvalId, name, version)。 */
    private String[] stageApproval() throws Exception {
        cm.initWorkflow("hitl-1", "hitl", "1.0", OWNER_HASH);
        svc.run("hitl-1", "hitl", "1.0", Map.of());
        assertThat(cm.findStatus("hitl-1")).contains(WorkflowStatus.AWAITING_APPROVAL);
        String approvalId = cm.findPendingApprovals("hitl-1").get(0).approvalId();
        return new String[]{"hitl-1", approvalId};
    }

    @Test
    @DisplayName("POST APPROVE → 工作流续跑至 SUCCESS（integration）")
    void approveResumesWorkflow() throws Exception {
        String[] ids = stageApproval();

        mockMvc.perform(post("/api/workflows/{wf}/approvals/{a}", ids[0], ids[1])
                        .header("X-API-Key", OWNER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk());

        assertThat(cm.findStatus("hitl-1")).contains(WorkflowStatus.SUCCESS);
        assertThat(cm.findApprovalById(ids[1])).get()
                .extracting(r -> r.status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    @DisplayName("POST REJECT → 工作流 FAILED")
    void rejectFilesWorkflow() throws Exception {
        String[] ids = stageApproval();

        mockMvc.perform(post("/api/workflows/{wf}/approvals/{a}", ids[0], ids[1])
                        .header("X-API-Key", OWNER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\"}"))
                .andExpect(status().isOk());

        assertThat(cm.findStatus("hitl-1")).contains(WorkflowStatus.FAILED);
    }

    @Test
    @DisplayName("GET pending：只含 PENDING、精简投影不含敏感载荷（requestPayload/contextSnapshot）")
    void pendingListIsProjection() throws Exception {
        String[] ids = stageApproval();
        // 给审批单塞一个敏感 requestPayload，验证即使有敏感值也不下发（DTO 无该字段）
        ApprovalRequest r = cm.findApprovalById(ids[1]).get();
        cm.saveApprovalRequest("hitl-1", ApprovalRequest.pending("hitl-1", r.nodeId(), r.round(),
                r.superStep(), r.description(), Map.of("secret", "amount-1000000"), r.contextSnapshot()));

        MvcResult res = mockMvc.perform(get("/api/workflows/{wf}/approvals/pending", ids[0])
                        .header("X-API-Key", OWNER_KEY))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        // JsonNode 解析：避免测试裸 mapper 缺 JSR-310 对 Instant 的类型绑定报错，且直接断言投影字段形状
        JsonNode root = mapper.readTree(body);
        // 投影不含：敏感 payload 值 + requestPayload/contextSnapshot 字段名
        assertThat(body).doesNotContain("1000000", "requestPayload", "contextSnapshot");
        // 每个元素只含投影字段（approvalId/nodeId/description/status/createdAt），且 status 均为 PENDING
        for (JsonNode v : root) {
            Set<String> fields = new java.util.LinkedHashSet<>();
            v.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder(
                    "approvalId", "nodeId", "description", "status", "createdAt");
            assertThat(v.get("status").asText()).isEqualTo("PENDING");
        }
        // 两个 PENDING：原 gate 审批 + 上面额外塞的（都在 pending，证明列表只过滤 PENDING）
        assertThat(root.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("请求体带 decidedBy → 忽略，审批记录 decidedBy = caller hash（防伪造）")
    void decidedByServerDerivedNonClientSpoofed() throws Exception {
        String[] ids = stageApproval();

        mockMvc.perform(post("/api/workflows/{wf}/approvals/{a}", ids[0], ids[1])
                        .header("X-API-Key", OWNER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"decidedBy\":\"impostor\"}"))
                .andExpect(status().isOk());

        assertThat(cm.findApprovalById(ids[1])).get()
                .extracting(r -> r.decidedBy()).isEqualTo(OWNER_HASH); // 服务端推导，忽略客户端
    }

    @Test
    @DisplayName("越权：非创建者非 admin 查/批 → 403")
    void unauthorizedIsForbidden() throws Exception {
        String[] ids = stageApproval();

        // intruder 决策 → 403
        mockMvc.perform(post("/api/workflows/{wf}/approvals/{a}", ids[0], ids[1])
                        .header("X-API-Key", INTRUDER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
        // intruder 查 pending → 403
        mockMvc.perform(get("/api/workflows/{wf}/approvals/pending", ids[0])
                        .header("X-API-Key", INTRUDER_KEY))
                .andExpect(status().isForbidden());
        // 状态未被越权者改动
        assertThat(cm.findStatus("hitl-1")).contains(WorkflowStatus.AWAITING_APPROVAL);
    }

    @Test
    @DisplayName("admin 可代批（复用 AGENTFLOW_ADMIN_API_KEYS 门控）")
    void adminCanDecide() throws Exception {
        String[] ids = stageApproval();

        mockMvc.perform(post("/api/workflows/{wf}/approvals/{a}", ids[0], ids[1])
                        .header("X-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk());

        assertThat(cm.findStatus("hitl-1")).contains(WorkflowStatus.SUCCESS);
        assertThat(cm.findApprovalById(ids[1])).get()
                .extracting(r -> r.decidedBy()).isEqualTo(ADMIN_HASH);
    }

    @Test
    @DisplayName("approval 归属与 workflow 不匹配 / 审批单不存在 → 400")
    void approvalNotBelongingToWorkflowGets400() throws Exception {
        String[] ids = stageApproval();
        // 另一工作流（不同 owner 下其实未被创建，但用不存在的 approvalId）→ 400
        mockMvc.perform(post("/api/workflows/{wf}/approvals/nonexistent", ids[0])
                        .header("X-API-Key", OWNER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("非法 decision 值 → 400")
    void invalidDecisionGets400() throws Exception {
        String[] ids = stageApproval();

        mockMvc.perform(post("/api/workflows/{wf}/approvals/{a}", ids[0], ids[1])
                        .header("X-API-Key", OWNER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
    }
}