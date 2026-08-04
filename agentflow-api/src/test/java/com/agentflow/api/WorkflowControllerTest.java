package com.agentflow.api;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.api.security.ApiKeyAuthFilter;
import com.agentflow.api.security.CallerToolAllowlist;
import com.agentflow.api.security.WorkflowOwnershipChecker;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.engine.checkpoint.WorkflowStatus;
import com.agentflow.version.InMemoryWorkflowDefinitionStore;
import com.agentflow.version.WorkflowVersionManager;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WorkflowController 单元测试（U14 接口对齐后补——原 U14 无 Controller 测试，覆盖率 0%）。
 *
 * <p>覆盖三个端点的关键路径：
 * <ul>
 *   <li>POST /workflows：合法/非法 YAML、工具授权拒绝、202 异步执行最终 SUCCESS</li>
 *   <li>GET /workflows/{id}/status：所有权校验（403 IDOR）、状态查询</li>
 *   <li>POST /workflows/{id}/retry：所有权 + 状态校验（仅 FAILED 可重试）</li>
 * </ul>
 */
class WorkflowControllerTest {

    private WorkflowDSLParser parser;
    private BspEngine engine;
    private InMemoryCheckpointManager checkpointManager;
    private WorkflowOwnershipChecker ownershipChecker;
    private CallerToolAllowlist toolAllowlist;
    private NodeRegistry nodeRegistry;
    private final WorkflowVersionManager versionManager =
            new WorkflowVersionManager(new InMemoryWorkflowDefinitionStore());

    private WorkflowController controller;

    @BeforeEach
    void setUp() {
        parser = new WorkflowDSLParser();
        engine = new BspEngine();
        checkpointManager = new InMemoryCheckpointManager();
        ownershipChecker = new WorkflowOwnershipChecker(checkpointManager);
        toolAllowlist = new CallerToolAllowlist(Map.of()); // 空 allowlist = 无限制
        nodeRegistry = new NodeRegistry(Map.of("a", input -> AgentOutput.of("output-from-a")));
        controller = new WorkflowController(parser, engine, checkpointManager,
                ownershipChecker, toolAllowlist, nodeRegistry, versionManager);
    }

    private static HttpServletRequest requestWithCaller(String callerId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(ApiKeyAuthFilter.CALLER_ID_ATTR)).thenReturn(callerId);
        return req;
    }

    // ─────────────────── POST /workflows ───────────────────

    @Test
    @DisplayName("提交合法 YAML → 202 + workflowId，异步执行最终 SUCCESS")
    void submitValidYamlReturnsAcceptedAndExecutes() throws Exception {
        String yaml = """
                agentflow: { version: "1.0" }
                nodes:
                  - { id: A, agent: a }
                edges: []
                """;
        WorkflowController.SubmitRequest body = new WorkflowController.SubmitRequest(
                "test-wf", "1.0", yaml, Map.of());

        var response = controller.submit(body, requestWithCaller("caller-A"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().workflowId()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("PENDING");

        // 异步执行最终 SUCCESS（轮询 checkpoint 状态）
        String wfId = response.getBody().workflowId();
        awaitStatus(wfId, WorkflowStatus.SUCCESS);
        assertThat(checkpointManager.findStatus(wfId)).contains(WorkflowStatus.SUCCESS);
    }

    @Test
    @DisplayName("提交非法 YAML → 400 + INVALID_YAML")
    void submitInvalidYamlReturns400() {
        WorkflowController.SubmitRequest body = new WorkflowController.SubmitRequest(
                "test-wf", "1.0", "not: valid: yaml: [", Map.of());

        var response = controller.submit(body, requestWithCaller("caller-A"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().status()).isEqualTo("INVALID_YAML");
    }

    @Test
    @DisplayName("提交引用未授权 Tool 的 YAML → 403 + FORBIDDEN")
    void submitWithUnauthorizedToolReturns403() {
        // allowlist 只允许 caller-A 用 toolA，提交引用 toolB
        toolAllowlist = new CallerToolAllowlist(Map.of("caller-A", Set.of("toolA")));
        controller = new WorkflowController(parser, engine, checkpointManager,
                ownershipChecker, toolAllowlist, nodeRegistry, versionManager);

        String yaml = """
                agentflow: { version: "1.0" }
                nodes:
                  - { id: A, agent: a, tools: [toolB] }
                edges: []
                """;
        WorkflowController.SubmitRequest body = new WorkflowController.SubmitRequest(
                "test-wf", "1.0", yaml, Map.of());

        var response = controller.submit(body, requestWithCaller("caller-A"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().status()).isEqualTo("FORBIDDEN");
    }

    // ─────────────────── GET /workflows/{id}/status ───────────────────

    @Test
    @DisplayName("非创建者查 status → 403（IDOR 防护）")
    void nonOwnerGetStatusReturns403() {
        // caller-A 创建工作流
        String wfId = submitWorkflow("caller-A");
        // caller-B 查 → 403
        var response = controller.getStatus(wfId, requestWithCaller("caller-B"));
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("创建者查 status → 200 + 状态")
    void ownerGetStatusReturns200() {
        String wfId = submitWorkflow("caller-A");
        var response = controller.getStatus(wfId, requestWithCaller("caller-A"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().workflowId()).isEqualTo(wfId);
        assertThat(response.getBody().status()).isNotNull();
    }

    // ─────────────────── POST /workflows/{id}/retry ───────────────────

    @Test
    @DisplayName("非创建者 retry → 403")
    void nonOwnerRetryReturns403() {
        String wfId = submitWorkflow("caller-A");
        var response = controller.retry(wfId, requestWithCaller("caller-B"));
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("retry 非 FAILED 的工作流 → 400")
    void retryNonFailedReturns400() throws Exception {
        String wfId = submitWorkflow("caller-A");
        awaitStatus(wfId, WorkflowStatus.SUCCESS); // 已 SUCCESS
        var response = controller.retry(wfId, requestWithCaller("caller-A"));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().message()).contains("仅 FAILED");
    }

    // ─────────────────── GET /workflows/{id}/version-check（U8） ───────────────────

    @Test
    @DisplayName("version-check：创建者 200 无冲突（执行版本=最新定义版本）")
    void versionCheckOwnerNoConflict() throws Exception {
        String wfId = submitWithVersion("ver-ok", "1.0");
        var res = controller.versionCheck(wfId, requestWithCaller("caller-A"));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().workflowName()).isEqualTo("ver-ok");
        assertThat(res.getBody().executedVersion()).isEqualTo("1.0");
        assertThat(res.getBody().conflict()).isFalse();
    }

    @Test
    @DisplayName("version-check：版本 bump 后旧实例报冲突（执行 1.0 vs 最新 2.0，WARN 不阻断）")
    void versionCheckReportsConflictWhenOutdated() throws Exception {
        String wfV1 = submitWithVersion("ver-conflict", "1.0");
        // 同 name 再提交 v2 → v1 执行实例 version-check 报 conflict
        submitWithVersion("ver-conflict", "2.0");
        var res = controller.versionCheck(wfV1, requestWithCaller("caller-A"));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().conflict()).isTrue();
        assertThat(res.getBody().latestVersion()).isEqualTo("2.0");
    }

    @Test
    @DisplayName("version-check：非创建者 → 403（IDOR 防护）")
    void versionCheckForbiddenForNonOwner() throws Exception {
        String wfId = submitWithVersion("ver-403", "1.0");
        var res = controller.versionCheck(wfId, requestWithCaller("caller-B"));
        assertThat(res.getStatusCode().value()).isEqualTo(403);
    }

    // ─────────────────── 辅助 ───────────────────

    private String submitWorkflow(String callerId) {
        String yaml = """
                agentflow: { version: "1.0" }
                nodes:
                  - { id: A, agent: a }
                edges: []
                """;
        WorkflowController.SubmitRequest body = new WorkflowController.SubmitRequest(
                "test-wf", "1.0", yaml, Map.of());
        return controller.submit(body, requestWithCaller(callerId)).getBody().workflowId();
    }

    /** 按指定版本提交工作流（U8 版本管理测试用），返回 workflowId。 */
    private String submitWithVersion(String name, String version) {
        String yaml = """
                agentflow: { version: "%s" }
                nodes:
                  - { id: A, agent: a }
                edges: []
                """.formatted(version);
        WorkflowController.SubmitRequest body = new WorkflowController.SubmitRequest(
                name, version, yaml, Map.of());
        var res = controller.submit(body, requestWithCaller("caller-A"));
        assertThat(res.getStatusCode().value()).isEqualTo(202);
        return res.getBody().workflowId();
    }

    /** 轮询工作流状态直到目标状态或超时（异步执行需等待）。 */
    private void awaitStatus(String wfId, WorkflowStatus target) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            var status = checkpointManager.findStatus(wfId);
            if (status.isPresent() && status.get() == target) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("工作流 " + wfId + " 未在 5s 内达到 " + target
                + "，当前状态: " + checkpointManager.findStatus(wfId));
    }
}
