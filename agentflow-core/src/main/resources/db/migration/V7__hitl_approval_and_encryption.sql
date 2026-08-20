-- V7__hitl_approval_and_encryption.sql
-- U1 HITL 审批（AWAITING_APPROVAL + workflow_approvals 表）
-- U7 R22 静态加密准备（checkpoint 敏感列 JSONB→TEXT，承载密文）

-- ============================================================
-- 1. workflow_executions.status 扩 AWAITING_APPROVAL
--    （V1 的 inline CHECK 自动命名为 workflow_executions_status_check，先 DROP 再重建）
-- ============================================================
ALTER TABLE workflow_executions DROP CONSTRAINT workflow_executions_status_check;
ALTER TABLE workflow_executions ADD CONSTRAINT workflow_executions_status_check
    CHECK (status IN ('PENDING', 'RUNNING', 'AWAITING_APPROVAL', 'SUCCESS', 'FAILED'));

-- ============================================================
-- 2. 审批表（HITL：待批单 + 上下文快照，供批准后恢复）
-- ============================================================
CREATE TABLE workflow_approvals (
    approval_id      TEXT        PRIMARY KEY,
    workflow_id      TEXT        NOT NULL,
    node_id          TEXT        NOT NULL,
    round            INT         NOT NULL DEFAULT 0,
    super_step       INT         NOT NULL,
    description      TEXT,
    request_payload  TEXT,        -- JSON（明文或 AESGCM:iv:ct，U7 加密）
    context_snapshot TEXT,        -- JSON（明文或 AESGCM:iv:ct，U7 加密）
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_by       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at       TIMESTAMPTZ
);

COMMENT ON TABLE workflow_approvals IS 'HITL 审批单：节点请求审批时持久化（含上下文快照，批准后恢复执行）';
COMMENT ON COLUMN workflow_approvals.context_snapshot IS '暂停点 channel 快照（含兄弟输出、不含审批节点），供 approveAndResume 重建 context';

-- 待办查询：按 workflow + 未决状态
CREATE INDEX idx_approval_wf_status
    ON workflow_approvals (workflow_id, status);

-- ============================================================
-- 3. R22 静态加密：checkpoint 敏感列 JSONB→TEXT（密文非合法 JSON）
-- ============================================================
ALTER TABLE workflow_node_outputs ALTER COLUMN output TYPE TEXT USING output::text;
ALTER TABLE workflow_checkpoints ALTER COLUMN channel_values TYPE TEXT USING channel_values::text;
