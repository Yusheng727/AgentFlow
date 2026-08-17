-- V5__loop_round.sql
-- v2 循环：checkpoint 加 round（迭代轮次）维度。
-- 无回边工作流 round 恒 0，旧行回填 round=0（向后兼容）。

-- 1. 节点级 checkpoint：加 round 列 + 唯一约束加 round
ALTER TABLE workflow_node_outputs ADD COLUMN round INT NOT NULL DEFAULT 0;
ALTER TABLE workflow_node_outputs DROP CONSTRAINT uq_node_output;
ALTER TABLE workflow_node_outputs ADD CONSTRAINT uq_node_output UNIQUE (workflow_id, round, super_step, node_id);

-- 2. barrier 级 checkpoint：加 round 列 + 唯一约束加 round
ALTER TABLE workflow_checkpoints ADD COLUMN round INT NOT NULL DEFAULT 0;
ALTER TABLE workflow_checkpoints DROP CONSTRAINT uq_workflow_checkpoint;
ALTER TABLE workflow_checkpoints ADD CONSTRAINT uq_workflow_checkpoint UNIQUE (workflow_id, round, super_step);

-- 3. 路由决策：加 round 列 + 主键改 (workflow_id, round)（按轮存每轮累计已走边）
ALTER TABLE workflow_routing_decisions ADD COLUMN round INT NOT NULL DEFAULT 0;
ALTER TABLE workflow_routing_decisions DROP CONSTRAINT workflow_routing_decisions_pkey;
ALTER TABLE workflow_routing_decisions ADD PRIMARY KEY (workflow_id, round);
