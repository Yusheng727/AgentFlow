package com.agentflow.version;

import com.agentflow.dsl.WorkflowDefinition;

import java.util.Optional;

/**
 * 工作流定义存储（U8，R14 + v4.3）。
 *
 * <p>按 {@code (workflowName, version)} 持久化"解析后的工作流定义 DAG"。提交时写入，
 * 恢复/retry 按 checkpoint 记录的 {@code workflow_name + workflow_version} 从本表取定义，
 * 不再从 classpath 读——版本 bump 后旧实例仍按旧 DAG 执行到结束。
 *
 * <p>实现：{@link InMemoryWorkflowDefinitionStore}（开发/测试/mock，进程内）、
 * {@link PostgresWorkflowDefinitionStore}（生产，{@code workflow_definitions} JSONB 表）。
 */
public interface WorkflowDefinitionStore {

    /** 按 (name, version) 取定义；无记录返回 empty。 */
    Optional<WorkflowDefinition> find(String workflowName, String version);

    /** 该 name 最新保存的定义（版本冲突检测用）；无记录返回 empty。 */
    Optional<WorkflowDefinition> findLatest(String workflowName);

    /** 保存定义（按 name+version，幂等 upsert）。 */
    void save(String workflowName, String version, WorkflowDefinition definition);
}
