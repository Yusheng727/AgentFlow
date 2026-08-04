package com.agentflow.version;

import com.agentflow.dsl.WorkflowDefinition;

import java.util.Optional;

/**
 * 工作流版本管理（U8，R14 + v4.3 orchestrator）。
 *
 * <p>组合 {@link WorkflowDefinitionStore} 与 {@link VersionConflictDetector}，对外暴露：
 * <ul>
 *   <li>{@link #recordWorkflowDefinition} — 提交时把本次定义按 (name, version) 存入 store</li>
 *   <li>{@link #loadDefinition} — 恢复/retry 按 (name, version) 取定义（不再从 classpath 读，R14 字面成立）</li>
 *   <li>{@link #detectConflict} — 版本 bump 检测（WARN 不阻断），供 version-check 端点</li>
 * </ul>
 */
public final class WorkflowVersionManager {

    private final WorkflowDefinitionStore store;
    private final VersionConflictDetector detector;

    public WorkflowVersionManager(WorkflowDefinitionStore store) {
        this(store, new VersionConflictDetector());
    }

    public WorkflowVersionManager(WorkflowDefinitionStore store, VersionConflictDetector detector) {
        this.store = store;
        this.detector = detector;
    }

    /** 提交时记录本次执行所用定义（按 name+version 存入 store）。 */
    public void recordWorkflowDefinition(String workflowName, WorkflowDefinition definition) {
        store.save(workflowName, definition.version(), definition);
    }

    /** 恢复/retry 按 (name, version) 取定义；无记录返回 empty。 */
    public Optional<WorkflowDefinition> loadDefinition(String workflowName, String version) {
        return store.find(workflowName, version);
    }

    /** 检测执行记录版本是否落后于该工作流最新定义版本（WARN 不阻断）。 */
    public Optional<VersionConflictDetector.Conflict> detectConflict(String workflowName, String executedVersion) {
        return detector.detect(store, workflowName, executedVersion);
    }
}
