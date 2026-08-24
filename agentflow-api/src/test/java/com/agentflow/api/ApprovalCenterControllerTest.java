package com.agentflow.api;

import com.agentflow.api.security.ApiKeyAuthFilter;
import com.agentflow.api.security.WorkflowOwnershipChecker;
import com.agentflow.engine.checkpoint.ApprovalDecision;
import com.agentflow.engine.checkpoint.ApprovalRequest;
import com.agentflow.engine.checkpoint.CheckpointManager;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.engine.checkpoint.WorkflowExecutionRecord;
import com.agentflow.engine.checkpoint.WorkflowStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U3 跨工作流待批聚合端点测试：GET /api/approvals/pending。
 *
 * <p>聚合语义：审批中心视图按调用者可见域聚合——非 admin 调用者只见<b>自己创建</b>的工作流
 * 的待批（经 listByCreatedBy(caller) 遍历），admin 见全部（createdBy=null=不过滤）。
 * 精简投影纪律延续（ApprovalView：不下发 requestPayload/contextSnapshot）。
 */
class ApprovalCenterControllerTest {

    private static final String OWNER_KEY = "owner-key-111";
    private static final String INTRUDER_KEY = "intruder-key-999";
    private static final String ADMIN_KEY = "admin-secret-777";

    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private InMemoryCheckpointManager cm;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        cm = new InMemoryCheckpointManager();
        // 两个工作流：owner 创建（含 2 待批），另一 creator 创建（含 1 待批）
        cm.initWorkflow("wf-own-1", "supplier-risk", "1.0", ApiKeyAuthFilter.sha256(OWNER_KEY));
        cm.updateStatus("wf-own-1", WorkflowStatus.AWAITING_APPROVAL);
        cm.saveApprovalRequest("wf-own-1", ApprovalRequest.pending(
                "wf-own-1", "pay-gate", 0, 1, "审批付款 ¥1000", Map.of("amount", 1000), Map.of()));
        cm.saveApprovalRequest("wf-own-1", ApprovalRequest.pending(
                "wf-own-1", "contract-gate", 0, 2, "审批合同条款", Map.of("clause", "x"), Map.of()));

        cm.initWorkflow("wf-other", "contract-review", "1.0", "other-creator-hash");
        cm.updateStatus("wf-other", WorkflowStatus.AWAITING_APPROVAL);
        cm.saveApprovalRequest("wf-other", ApprovalRequest.pending(
                "wf-other", "risk-gate", 0, 1, "别人的审批", Map.of("k", "v"), Map.of()));

        ApprovalCenterController controller = new ApprovalCenterController(
                cm, new WorkflowOwnershipChecker(cm), ADMIN_KEY);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new ApiKeyAuthFilter(java.util.Set.of(OWNER_KEY, INTRUDER_KEY, ADMIN_KEY)))
                .build();
    }

    @Test
    @DisplayName("创建者：聚合只见自己工作流的待批（2 条），含 workflowId，不含敏感载荷")
    void creatorSeesOnlyOwnPendingApprovals() throws Exception {
        String body = mockMvc.perform(get("/api/approvals/pending").header("X-API-Key", OWNER_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var arr = mapper.readTree(body);
        assertThat(arr.size()).isEqualTo(2);
        assertThat(arr.toString()).contains("wf-own-1");
        assertThat(arr.toString()).doesNotContain("wf-other");
        // 精简投影：workflowId/nodeId/description/status/createdAt 有，载荷无
        for (var node : arr) {
            assertThat(node.has("workflowId")).isTrue();
            assertThat(node.has("nodeId")).isTrue();
            assertThat(node.has("description")).isTrue();
            assertThat(node.has("status")).isTrue();
            assertThat(node.has("requestPayload")).isFalse();
            assertThat(node.has("contextSnapshot")).isFalse();
        }
    }

    @Test
    @DisplayName("admin：见全部工作流的待批（3 条）")
    void adminSeesAllPendingApprovals() throws Exception {
        String body = mockMvc.perform(get("/api/approvals/pending").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var arr = mapper.readTree(body);
        assertThat(arr.size()).isEqualTo(3);
        assertThat(arr.toString()).contains("wf-own-1").contains("wf-other");
    }

    @Test
    @DisplayName("非创建者非 admin：聚合只看自己的 → 空（不是 403；不泄漏他人审批存在性）")
    void nonCreatorNonAdminSeesEmpty() throws Exception {
        String body = mockMvc.perform(get("/api/approvals/pending").header("X-API-Key", INTRUDER_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(body).size()).isZero();
    }

    @Test
    @DisplayName("无 API key：401（鉴权过滤链拦截）")
    void missingApiKeyRejected() throws Exception {
        mockMvc.perform(get("/api/approvals/pending")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("无待批的调用者：空数组（200 非 404）")
    void noPendingReturnsEmptyArray() throws Exception {
        cm.findPendingApprovals("wf-own-1").forEach(r ->
                cm.confirmApproval(r.approvalId(), ApprovalDecision.APPROVE, "x"));
        String body = mockMvc.perform(get("/api/approvals/pending").header("X-API-Key", OWNER_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(body).size()).isZero();
    }

    @Test
    @DisplayName("P1 修复：单工作流待批查询异常时跳过该 wf，聚合仍 200 返回部分结果")
    void corruptWorkflowRowSkippedNotWhole500() throws Exception {
        CheckpointManager failing = mock(CheckpointManager.class);
        // admin 调用（listByCreatedBy(null)）→ 遍历全部两个工作流；wf-own-1 待批查询抛异常被跳过
        when(failing.listByCreatedBy(null)).thenReturn(List.of(
                new WorkflowExecutionRecord("wf-own-1", "supplier-risk", WorkflowStatus.AWAITING_APPROVAL, Instant.now()),
                new WorkflowExecutionRecord("wf-other", "contract-review", WorkflowStatus.AWAITING_APPROVAL, Instant.now())));
        when(failing.findPendingApprovals("wf-own-1"))
                .thenThrow(new IllegalStateException("AES-GCM 解密失败：wf-own-1"));
        when(failing.findPendingApprovals("wf-other")).thenReturn(List.of(
                com.agentflow.engine.checkpoint.ApprovalRequest.pending(
                        "wf-other", "risk-gate", 0, 1, "别人的审批", Map.of("k", "v"), Map.of())));

        ApprovalCenterController controller = new ApprovalCenterController(
                failing, new com.agentflow.api.security.WorkflowOwnershipChecker(failing), ADMIN_KEY);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new ApiKeyAuthFilter(java.util.Set.of(OWNER_KEY, ADMIN_KEY)))
                .build();

        String body = mvc.perform(get("/api/approvals/pending").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // wf-own-1 坏行被跳过，wf-other 的待批仍返回（200 而非 500）
        var arr = mapper.readTree(body);
        assertThat(arr.size()).isEqualTo(1);
        assertThat(arr.get(0).get("workflowId").asText()).isEqualTo("wf-other");
    }

    @Test
    @DisplayName("P2 修复：workflowName 从工作流记录真实填充（原恒 null，7 reviewer）")
    void workflowNamePopulatedFromRecord() throws Exception {
        String body = mockMvc.perform(get("/api/approvals/pending").header("X-API-Key", OWNER_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var arr = mapper.readTree(body);
        assertThat(arr.size()).isEqualTo(2);
        for (var node : arr) {
            assertThat(node.has("workflowName")).isTrue();
            assertThat(node.get("workflowName").asText()).isEqualTo("supplier-risk");
        }
    }
}