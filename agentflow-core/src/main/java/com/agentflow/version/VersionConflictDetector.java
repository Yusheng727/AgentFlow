package com.agentflow.version;

import com.agentflow.dsl.WorkflowDefinition;

import java.util.Optional;

/**
 * 版本冲突检测（U8，R14）：比较一次执行记录的版本与该工作流最新定义版本。
 *
 * <p>语义：版本 bump 后，已运行/恢复中的实例仍按各自记录的版本执行；只有落后于该
 * {@code workflow_name} 最新版本时记为一个 {@link Conflict}（调用方 WARN 日志，不阻断）。
 */
public final class VersionConflictDetector {

    /** 冲突描述：执行版本 ≠ 该工作流最新定义版本（WARN 级别，不阻断）。 */
    public record Conflict(String workflowName, String executedVersion, String latestVersion, String message) {
    }

    /**
     * 检测执行记录版本是否落后于该工作流最新定义版本。
     *
     * @return 存在冲突 → {@link Conflict}；无最新定义或一致 → empty
     */
    public Optional<Conflict> detect(WorkflowDefinitionStore store, String workflowName, String executedVersion) {
        Optional<WorkflowDefinition> latest = store.findLatest(workflowName);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        String latestVersion = latest.get().version();
        if (latestVersion.equals(executedVersion)) {
            return Optional.empty();
        }
        return Optional.of(new Conflict(workflowName, executedVersion, latestVersion,
                String.format("工作流 %s 执行版本 %s 落后于最新定义版本 %s（WARN，按已运行版本继续执行到结束）",
                        workflowName, executedVersion, latestVersion)));
    }
}
