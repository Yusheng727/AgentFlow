-- V8__r22_encrypt_routing_and_definitions.sql
-- U1/U2 R22 扩列：路由决策 + 工作流定义列加密（JSONB→TEXT，承载 AESGCM: 密文）
--
-- 对齐 V7 先例（checkpoint 敏感列 JSONB→TEXT）：密文非合法 JSON，列型必须 TEXT。
-- 加解密在应用层边界（PostgresCheckpointManager / PostgresWorkflowDefinitionStore 的
-- ColumnEncryptor），无 key（Noop）时仍存明文 JSON 文本——TEXT 列对明文 JSON 兼容。
-- legacy 明文行（升级前 JSONB 数据自动转 TEXT）读路径原样返回，不碎裂。

ALTER TABLE workflow_routing_decisions ALTER COLUMN decisions TYPE TEXT;
ALTER TABLE workflow_definitions ALTER COLUMN definition TYPE TEXT;

COMMENT ON COLUMN workflow_routing_decisions.decisions IS '已走边列表（JSON，明文或 AESGCM:iv:ct 密文，V8 起 R22 列加密）';
COMMENT ON COLUMN workflow_definitions.definition IS 'WorkflowDefinition 序列化（JSON，明文或 AESGCM:iv:ct 密文，V8 起 R22 列加密）';
