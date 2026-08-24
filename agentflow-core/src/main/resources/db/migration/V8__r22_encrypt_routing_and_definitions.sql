-- V8__r22_encrypt_routing_and_definitions.sql
-- U1/U2 R22 扩列：路由决策 + 工作流定义列加密（JSONB→TEXT，承载 AESGCM: 密文）
--
-- 对齐 V7 先例（checkpoint 敏感列 JSONB→TEXT）：密文非合法 JSON，列型必须 TEXT。
-- 用显式 USING（对齐 V7 的 USING ...::text）而非隐式 assignment cast——防既有 JSONB 行
-- 在 cast 时依赖隐式序列化语义、并保持 V7 已验模式的逐字一致（review P2）。
ALTER TABLE workflow_routing_decisions ALTER COLUMN decisions TYPE TEXT USING decisions::text;
ALTER TABLE workflow_definitions ALTER COLUMN definition TYPE TEXT USING definition::text;

COMMENT ON COLUMN workflow_routing_decisions.decisions IS '已走边列表（JSON，明文或 AESGCM:iv:ct 密文，V8 起 R22 列加密）';
COMMENT ON COLUMN workflow_definitions.definition IS 'WorkflowDefinition 序列化（JSON，明文或 AESGCM:iv:ct 密文，V8 起 R22 列加密）';
