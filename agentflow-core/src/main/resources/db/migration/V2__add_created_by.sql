-- V2__add_created_by.sql
-- U14 API 鉴权：workflow_executions 增加 created_by 列，存 X-API-Key 的 SHA-256 hash
--
-- 用于 WorkflowOwnershipChecker 做 per-workflow 所有权校验（防 IDOR）：
-- API Key A 创建的 workflow，API Key B 无法查询/重试。

ALTER TABLE workflow_executions
    ADD COLUMN IF NOT EXISTS created_by TEXT;

COMMENT ON COLUMN workflow_executions.created_by IS '创建者 API Key 的 SHA-256 hash（U14 WorkflowOwnershipChecker 用于防 IDOR）';

-- 索引：按创建者查询自己的 workflows
CREATE INDEX IF NOT EXISTS idx_workflow_created_by
    ON workflow_executions (created_by);