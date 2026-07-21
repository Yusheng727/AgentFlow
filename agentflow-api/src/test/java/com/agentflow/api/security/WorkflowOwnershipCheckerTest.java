package com.agentflow.api.security;

import com.agentflow.agent.AgentOutput;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.engine.checkpoint.CheckpointManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U14 WorkflowOwnershipChecker 测试（plan U14 Test scenarios）。
 */
class WorkflowOwnershipCheckerTest {

    private StubCheckpointManager cm;
    private WorkflowOwnershipChecker checker;

    @BeforeEach
    void setUp() {
        cm = new StubCheckpointManager();
        checker = new WorkflowOwnershipChecker(cm);
    }

    @Test
    @DisplayName("创建者本人 → isOwner=true")
    void ownerIsOwner() {
        cm.initWorkflow("wf-1", "test", "1.0", "caller-a");

        assertThat(checker.isOwner("wf-1", "caller-a")).isTrue();
    }

    @Test
    @DisplayName("非创建者 → isOwner=false（IDOR 防护）")
    void nonOwnerIsNotOwner() {
        cm.initWorkflow("wf-1", "test", "1.0", "caller-a");

        assertThat(checker.isOwner("wf-1", "caller-b")).isFalse();
    }

    @Test
    @DisplayName("workflow 不存在 → isOwner=false")
    void missingWorkflowIsNotOwner() {
        assertThat(checker.isOwner("wf-nonexistent", "caller-a")).isFalse();
    }

    @Test
    @DisplayName("requireOwnership 非创建者 → 抛 OwnershipException")
    void requireOwnershipThrowsWhenNotOwner() {
        cm.initWorkflow("wf-1", "test", "1.0", "caller-a");

        assertThatThrownBy(() -> checker.requireOwnership("wf-1", "caller-b"))
                .isInstanceOf(WorkflowOwnershipChecker.OwnershipException.class)
                .hasMessageContaining("wf-1");
    }

    @Test
    @DisplayName("requireOwnership 创建者 → 不抛异常")
    void requireOwnershipPassesWhenOwner() {
        cm.initWorkflow("wf-1", "test", "1.0", "caller-a");

        checker.requireOwnership("wf-1", "caller-a"); // 不应抛异常
    }

    @Test
    @DisplayName("API Key A 创建 → API Key B 无法访问（防 IDOR 场景）")
    void idorPrevention() {
        String keyA = "caller-a-hash";
        String keyB = "caller-b-hash";

        cm.initWorkflow("wf-1", "supplier-risk", "1.0", keyA);

        // Key B 尝试查状态 → 拒绝
        assertThat(checker.isOwner("wf-1", keyB)).isFalse();
        assertThatThrownBy(() -> checker.requireOwnership("wf-1", keyB))
                .isInstanceOf(WorkflowOwnershipChecker.OwnershipException.class);

        // Key A 正常访问
        assertThat(checker.isOwner("wf-1", keyA)).isTrue();
    }

    @Test
    @DisplayName("多 workflow 隔离：各自只能访问自己的")
    void multiWorkflowIsolation() {
        cm.initWorkflow("wf-1", "wf1", "1.0", "caller-a");
        cm.initWorkflow("wf-2", "wf2", "1.0", "caller-b");

        assertThat(checker.isOwner("wf-1", "caller-a")).isTrue();
        assertThat(checker.isOwner("wf-1", "caller-b")).isFalse();
        assertThat(checker.isOwner("wf-2", "caller-b")).isTrue();
        assertThat(checker.isOwner("wf-2", "caller-a")).isFalse();
    }

    @Nested
    @DisplayName("callerIdFrom 提取")
    class CallerIdExtraction {

        @Test
        @DisplayName("request attribute 存在 → 返回 callerId")
        void extractsCallerId() {
            jakarta.servlet.http.HttpServletRequest request =
                    new org.springframework.mock.web.MockHttpServletRequest();
            request.setAttribute(ApiKeyAuthFilter.CALLER_ID_ATTR, "abc123");

            assertThat(WorkflowOwnershipChecker.callerIdFrom(request)).isEqualTo("abc123");
        }

        @Test
        @DisplayName("request attribute 不存在 → 返回 null")
        void returnsNullWhenAbsent() {
            jakarta.servlet.http.HttpServletRequest request =
                    new org.springframework.mock.web.MockHttpServletRequest();
            assertThat(WorkflowOwnershipChecker.callerIdFrom(request)).isNull();
        }
    }

    // ─────────────────── 测试用 Stub CheckpointManager ───────────────────

    /** 最小 CheckpointManager 实现，仅支持工作流生命周期 + 所有权查询。 */
    private static class StubCheckpointManager implements CheckpointManager {

        private final ConcurrentHashMap<String, String> workflowStatus = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, String> workflowCreatedBy = new ConcurrentHashMap<>();

        @Override
        public void initWorkflow(String workflowId, String workflowName, String version, String createdBy) {
            workflowStatus.put(workflowId, "PENDING");
            workflowCreatedBy.put(workflowId, createdBy);
        }

        @Override
        public void updateStatus(String workflowId, String status) {
            workflowStatus.put(workflowId, status);
        }

        @Override
        public Optional<String> findCreatedBy(String workflowId) {
            return Optional.ofNullable(workflowCreatedBy.get(workflowId));
        }

        @Override
        public Optional<String> findStatus(String workflowId) {
            return Optional.ofNullable(workflowStatus.get(workflowId));
        }

        // ── 未实现的方法（本测试不涉及） ──

        @Override public void saveNodeOutput(String w, int s, String n, AgentOutput o) {}
        @Override public void saveBarrier(String w, int s, WorkflowContext c) {}
        @Override public Optional<?> findLatestBarrier(String w) { return Optional.empty(); }
        @Override public List<?> findCompletedNodes(String w, int s) { return List.of(); }
    }
}