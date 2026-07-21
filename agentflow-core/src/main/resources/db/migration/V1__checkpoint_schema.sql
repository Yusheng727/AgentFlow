-- V1__checkpoint_schema.sql
-- U5 两级 Checkpoint 数据库 schema
--
-- 三张表：
-- 1. workflow_executions    — 工作流实例级元数据（状态、版本）
-- 2. workflow_node_outputs  — 节点级 checkpoint（单个 Agent 执行结果，COMPLETED 同步写）
-- 3. workflow_checkpoints   — barrier 级 checkpoint（super-step 完成后 channel 快照）

-- ============================================================
-- 1. 工作流执行实例
-- ============================================================
CREATE TABLE workflow_executions (
    id              TEXT        PRIMARY KEY,
    workflow_name   TEXT        NOT NULL,
    workflow_version TEXT       NOT NULL DEFAULT '1.0',
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE workflow_executions IS '工作流执行实例元数据（U5 落地，U14 加 created_by 列）';
COMMENT ON COLUMN workflow_executions.status IS 'PENDING | RUNNING | SUCCESS | FAILED';

-- ============================================================
-- 2. 节点级 Checkpoint（COMPLETED 同步写入，Semaphore(20) 限流）
-- ============================================================
CREATE TABLE workflow_node_outputs (
    id               BIGSERIAL   PRIMARY KEY,
    workflow_id      TEXT        NOT NULL,
    super_step       INT         NOT NULL,
    node_id          TEXT        NOT NULL,
    output           JSONB,
    status           VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS'
                     CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    tokens_consumed  INT,
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 幂等保证：同一 workflow + super_step + node_id 只能有一条记录
    CONSTRAINT uq_node_output UNIQUE (workflow_id, super_step, node_id)
);

COMMENT ON TABLE workflow_node_outputs IS '节点级 checkpoint：Agent 执行完毕立即持久化（R3）';
COMMENT ON COLUMN workflow_node_outputs.output IS 'AgentOutput 序列化为 JSONB（content + channelWrites + structuredOutput + metadata）';
COMMENT ON COLUMN workflow_node_outputs.status IS 'IN_PROGRESS | COMPLETED | FAILED（COMPLETED 终态不可覆盖）';

-- 查询索引：按 workflow + super_step 查崩溃层的已完成节点（RecoveryProtocol）
CREATE INDEX idx_node_output_wf_step
    ON workflow_node_outputs (workflow_id, super_step, status)
    WHERE status = 'COMPLETED';

-- ============================================================
-- 3. Barrier 级 Checkpoint（super-step 完成后 channel 快照）
-- ============================================================
CREATE TABLE workflow_checkpoints (
    id              BIGSERIAL   PRIMARY KEY,
    workflow_id     TEXT        NOT NULL,
    super_step      INT         NOT NULL,
    channel_values  JSONB       NOT NULL,
    completed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE workflow_checkpoints IS 'Barrier 级 checkpoint：super-step 完成后 channel 快照（仅成功 super-step 写入）';
COMMENT ON COLUMN workflow_checkpoints.super_step IS '已完成的 super-step 编号（0-based）。step=k 表示 super-step k 已 barrier 合并完成';

-- Recovery 查询：按 workflow 查最新 barrier checkpoint
CREATE INDEX idx_checkpoint_wf_step
    ON workflow_checkpoints (workflow_id, super_step DESC);