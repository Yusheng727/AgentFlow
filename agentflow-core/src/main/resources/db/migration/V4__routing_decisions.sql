-- V4__routing_decisions.sql
-- v2 条件分支：持久化路由决策（已走边 from→to，累计列表）
--
-- 恢复期用「已走路径 + 静态图」确定性重算 SKIPPED 集合（KTD-5 单一真相源），
-- 不单独落 SKIPPED 态。每 barrier 后写入累计列表，upsert 覆盖旧值（latest wins）。

CREATE TABLE workflow_routing_decisions (
    workflow_id  TEXT        PRIMARY KEY,
    super_step   INT         NOT NULL,
    decisions    JSONB       NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE workflow_routing_decisions IS 'v2 条件分支：路由决策（已走边 from→to，累计列表），恢复期重算 SKIPPED';
COMMENT ON COLUMN workflow_routing_decisions.decisions IS '已走边列表（from->to 字符串数组，累计），saveRoutingDecisions 每 barrier 覆盖';
