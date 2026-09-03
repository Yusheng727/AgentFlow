# 贡献指南

感谢关注 AgentFlow！这是一个从零实现的 Java 原生 Multi-Agent 编排引擎（BSP 执行模型 + 两级 Checkpoint），欢迎 bug 报告、文档改进与功能讨论。

## 开发环境

- **JDK 21**（项目使用 Virtual Threads，必须 21+）
- **Maven 3.9+**
- **Node.js 20+**（仅改 `agentflow-ui/` 时需要）
- Docker（可选，用于本地起 PostgreSQL/Kafka/Grafana）

## 构建与测试

```bash
mvn -B -ntp verify                    # 全量：编译 + 单元 + 集成 + JaCoCo 80% 覆盖率门禁
mvn -B -ntp -pl agentflow-core test   # 只跑 core 模块测试
cd agentflow-ui && npm test           # UI 单元测试（Vitest）
cd agentflow-ui && npm run build      # UI 构建（tsc strict）
```

**集成测试**（PostgreSQL 相关的 Failsafe IT）默认在无 PG 环境自动跳过；本地跑全量：

```bash
docker compose -f docker-compose.test.yml up -d
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/agentflow mvn verify
```

## 工程约定

- **模块结构**：`agentflow-core`（引擎/DSL/Checkpoint/安全）不含任何 LLM 框架依赖；所有框架调用收敛在 `agentflow-adapters/` 的适配器内。新增框架能力先想清楚落在哪一层。
- **包名**：`com.agentflow.<module-feature>`（`dsl` / `engine` / `agent` / `observability` / `security` / `api` / `version`）。
- **POJO**：Java records，不可变。YAML 字段 SNAKE_CASE（`prompt_template` ↔ `promptTemplate`）。
- **测试**：JUnit 5 + AssertJ。新功能必须带测试，bug 修复先写复现测试。
- **覆盖率**：JaCoCo INSTRUCTION ≥ 80%（BUNDLE 维度），`mvn verify` 强制。
- **安全**：凭证只从环境变量读，禁止写入 yml/代码；新增落库字段先过「是否敏感列」判断（见 `docs/business/flows/column-encryption.md`）。

## 提交规范

Conventional Commits：`feat(scope):` / `fix(scope):` / `refactor:` / `docs:` / `test:` / `chore:`。

- 分支：feature 分支 `feat/<name>`，修 bug 用 `fix/<name>`，从 `main` 切出。
- 提交前本地 `mvn verify` 必须绿。
- 描述写「为什么」而非仅「做了什么」——本项目重视决策可追溯性（见 `docs/developer-notes/01-implementation-rationale.md`）。

## 架构决策与设计取舍

动核心引擎前建议先读：

- `docs/plans/agentflow/03-key-technical-decisions.md` — KTD-1~9（BSP 选型、Checkpoint 设计、适配器窄表面等）
- `docs/developer-notes/01-implementation-rationale.md` — 每个关键决策的「为什么 + 替代方案 + 为什么不选」
- `docs/developer-notes/02-bugs-and-fixes.md` — 已知坑与根因，避免重复踩

不认同某个设计决策？欢迎开 Issue 讨论——带着你的替代方案来。

## 报告 Bug

开 Issue 请附：复现步骤、期望行为、实际行为、`mvn verify` 是否绿。有 ExecutionTrace（`GET /api/workflows/{id}/trace`）输出的贴上来更好。
