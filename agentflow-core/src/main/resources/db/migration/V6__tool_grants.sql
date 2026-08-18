-- V6__tool_grants.sql
-- v1.1 R21：工具级授权升级——CallerToolAllowlist 从 config/env 硬编码升级为 DB 表 + 管理 API。
-- 按 (caller_id, tool_name) 唯一；caller_id 是 X-API-Key 的 SHA-256 hash（对齐 workflow_executions.created_by）。

CREATE TABLE caller_tool_grants (
    caller_id   VARCHAR(64)  NOT NULL,
    tool_name   VARCHAR(128) NOT NULL,
    granted_by  VARCHAR(64),           -- 授权方 caller hash（管理 API 的 admin key hash），可空（早期直接插库）
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (caller_id, tool_name)
);

-- 按 caller 查工具授权的索引（管理 API 列表 + 运行时 isGranted 查询）
CREATE INDEX idx_tool_grants_caller ON caller_tool_grants (caller_id);
