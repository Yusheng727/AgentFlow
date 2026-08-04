-- V3__workflow_definitions.sql
-- U8 Workflow 版本管理（R14，v4.3 卡点）
--
-- 按 (workflow_name, version) 存"解析后的工作流定义 DAG"，提交时写入；
-- 恢复/retry 按 checkpoint 记录的 workflow_name + workflow_version 从本表取定义，
-- 不再从 classpath 读——保证 YAML 版本 bump 后，已运行/崩溃恢复的旧实例仍按旧 DAG 执行到结束。

CREATE TABLE workflow_definitions (
    workflow_name   TEXT        NOT NULL,
    version         TEXT        NOT NULL,
    definition      JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_workflow_definition PRIMARY KEY (workflow_name, version)
);

COMMENT ON TABLE workflow_definitions IS '按 (workflow_name, version) 存解析后的工作流定义（U8，R14 版本管理的定义来源）';
COMMENT ON COLUMN workflow_definitions.definition IS 'WorkflowDefinition 序列化为 JSONB（DSL POJO: agentflow/channels/nodes/edges）';

-- 按 name 查最新 definition（版本冲突检测 VersionConflictDetector 用）
CREATE INDEX idx_workflow_definition_name
    ON workflow_definitions (workflow_name, created_at DESC);
