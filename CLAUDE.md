# AgentFlow — 接手指南（给 Claude Code）

> 本文件让接手本项目的 Claude Code 会话快速读懂现状并继续工作。读完这一份 + `docs/plans/agentflow/` 就能动手。最后更新：2026-07-03（U2 合并 + U3 开工）。

## 这是什么项目

AgentFlow = **Java 原生轻量级 Multi-Agent 编排引擎**。YAML DSL 声明工作流，BSP（Bulk Synchronous Parallel）执行模型驱动，两级 Checkpoint + Recovery Protocol，多 Agent 像微服务一样协作。

- **定位**：简历三项目之一（ToyRush 高并发基础 / InterviewCoach AI Agent+RAG / **AgentFlow 后端工程化 70% + Agent 30%**）。**从0复现展示后端工程深度**，不填补生态空白——LangGraph4j / Spring AI Alibaba 已存在，价值在工程深度而非空白。
- **v1 范围**：静态 DAG（串行+并行+混合）+ PostgreSQL 持久化 Checkpoint + 调试体验，10 周交付，15 个实现单元（U0–U14）。
- **栈**：Java 21（Virtual Threads）/ Spring Boot 4.1.0（U3 起 bump：Spring AI 2.0 需 Spring Framework 7 + Jackson 3）/ Spring AI 2.0.0 GA / PostgreSQL / Redis / Maven 多模块。

## 当前进度

**计划文档**：`docs/plans/agentflow/`（8 个分片，`00-overview.md` 是索引+导航）。两轮 ce-doc-review 闭环 + 5 条动工前卡点拍板，**0 动工阻塞**。

**已落地（main 分支 8 commit，feat/u3-agent-adapter 进行中）**：
| Unit | 内容 | 验证 |
|:---|:---|:---|
| — | 计划文档纳管 | — |
| 脚手架 | Maven 多模块（parent + core/adapters-spring-ai/api/starter） | `mvn validate` 5 模块 SUCCESS |
| U0 | CI/CD：`.github/workflows/ci.yml` + `performance-benchmark.yml` + `sonar-project.properties` + `docker-compose.test.yml` + JaCoCo/Failsafe 插件 | `mvn verify` SUCCESS |
| U1 | YAML DSL：`com.agentflow.dsl` 包，8 POJO + 解析器 + 三层校验 + 最长路径分层 + JSON Schema + 13 测试 | 13 tests pass，JaCoCo 80% 达标 |
| U2 | BSP 执行引擎：`com.agentflow.agent`（AgentFunction/AgentInput/AgentOutput + 异常合约）+ `com.agentflow.engine`（BspEngine/NodeExecutor/WorkflowContext/ChannelReducer/DAGraph/SuperStep/NodeResult）+ `engine.checkpoint` seam。Virtual Threads 并行 + CompletableFuture.allOf barrier + 只读快照 + Reducer 确定性合并 + 异常隔离。经 8 人 ce-code-review + 11 修复（null output/inputs 透传/catch-all/cancel 守卫/parseTimeout 零负值/CONCAT 扁平/MAX 精度/失败层不写 barrier） | 57 tests pass，JaCoCo 80% 达标，`mvn verify` 5 模块 SUCCESS |
| U3 地基 | bump Spring Boot 3.4→4.1.0（Spring AI 2.0 需 Spring Framework 7 + Jackson 3）+ spring-ai-bom 2.0.0 import + spring-ai-starter-model-openai 加到 adapter 模块。KTD-7 冒烟通过：ChatClient + Advisor Chain（BaseAdvisor before/after）+ @Tool 注册（MethodToolCallbackProvider）+ usage 捕获 | `mvn verify` Boot 4.1 下 5 模块全绿（core 57 + adapter 冒烟 3） |

**OQ-2 决议**：plan 当初猜 Spring AI 2.0 要 Boot 3.5——实际不够。3.5 仍带 Jackson 2.19，而 Spring AI 2.0.0 的 @Tool schema 路径用 Jackson 3（`tools.jackson.core`，需 `JsonSerializeAs`）。**正确版本是 Spring Boot 4.1.0 GA**（自带 Jackson 3.1.4 + Spring Framework 7）。U3 起全仓升 Boot 4.1。

**当前单元**：**U3 Agent 适配器层**（Week 3-4，P0）。OQ-2 已解决（Spring AI 2.0 GA 可拉，Boot 3.5）。按 plan：SpringAiAgentAdapter + Advisor Chain（TokenCountingAdvisor/LoggingAdvisor）+ OutputSchemaValidator + NodeRegistry + ExecutionTrace。**含 ce-code-review 留的 seam**：AgentInput 加 tools/outputSchema 字段、AgentOutput 加 structuredOutput + metadata（token 统计）。KTD-7 要求先做 Advisor Chain + @Tool 冒烟再开建。

**后续顺序**（按 `05-implementation-units.md` 的 Unit Priority 矩阵 P0 先行）：
U3（Agent 适配器）→ U4（容错）→ U5（Checkpoint+Recovery）→ U10（主 Demo）→ U13（Starter）→ U14（安全）。P1/P2（U6/U7/U8/U9/U11/U12）跟进。

**U2 留给后续单元的 seam（实现时已决策，非 plan 偏离，记此备查）**：
- `AgentFunction`/`AgentInput`/`AgentOutput`/异常类型由 U2 引入最小合约（BSP 引擎执行节点必需），U3 富化 `AgentOutput.structuredOutput` 字段 + 提供 `SpringAiAgentAdapter` 实现 + `NodeRegistry`（作为 `Map<String,AgentFunction>` 来源）+ `OutputSchemaValidator`/`LoggingAdvisor`/`TokenCountingAdvisor`。
- `CheckpointManager` 接口 + `NoopCheckpointManager` 由 U2 引入（BSP 循环持久化 seam），U5 提供 `PostgresCheckpointManager`/`InMemoryCheckpointManager`/`RecoveryProtocol` + DB migration；U5 可在此接口上扩展 `recover(...)`。
- `NodeExecutor` 已做节点级超时 + `cancel(true)` 中断 + `AgentFunction.cancel()`；U4 的 `RetryPolicy`/`ErrorClassifier`/`ErrorHandler` 在其外层包裹（retry 预算与 schema-retry 共享计数器）。

**仍开放**：13 条叙事/范围类 Open Questions（`06-open-questions-risks-metrics.md` 的 `### From 2026-06-28 review`），演示前定即可，不挡动工。

## 仓库结构

```
AgentFlow/
├── pom.xml                      # parent（Spring Boot 4.1.0 + Java 21 + JaCoCo/Failsafe；spring-ai-bom 2.0.0 import）
├── settings.xml                 # 本地 Maven 镜像（HTTPS alimaven，gitignored，非项目配置）
├── CLAUDE.md                    # 本文件
├── README.md                    # GitHub 门面
├── .github/workflows/           # U0 CI
├── docker-compose.test.yml      # 本地集成测试 PG+Redis
├── sonar-project.properties
├── docs/plans/agentflow/        # 计划文档（8 分片，权威 source of truth）
│   ├── 00-overview.md           # 索引+导航
│   ├── 01-problem-frame.md      # Problem Frame / 简历定位
│   ├── 02-requirements.md       # R1–R22
│   ├── 03-key-technical-decisions.md  # KTD-1~9
│   ├── 04-high-level-design.md  # 架构图 / BSP / ER / 状态机
│   ├── 05-implementation-units.md    # U0–U14 + Priority 矩阵（执行用）
│   ├── 06-open-questions-risks-metrics.md
│   └── 07-sources-revision-interview.md
├── agentflow-core/              # BSP/DSL/Checkpoint/容错/可观测/安全
│   └── src/main/java/com/agentflow/dsl/   # U1 已落地
├── agentflow-adapters/spring-ai/# SpringAiAgentAdapter（U3 引入 Spring AI 2.0）
├── agentflow-api/               # REST 端点 + 鉴权（U6/U14）
└── agentflow-starter/           # @EnableAgentFlow + AutoConfiguration（U9/U13）
```

## 怎么构建 / 测试

```bash
# 工具链：JDK 21 + Maven 3.9+（确认 java -version / mvn -version）
# 国内网络：本仓 settings.xml 把 central 镜像到 HTTPS alimaven（gitignored，CI 不需要）
mvn -s settings.xml -B -ntp validate          # 仅校验结构
mvn -s settings.xml -B -ntp compile           # 编译（首次拉依赖较慢）
mvn -s settings.xml -B -ntp test              # 单元测试
mvn -s settings.xml -B -ntp verify            # 单元+集成+JaCoCo 80% 门禁（完整 verify）
mvn -s settings.xml -B -ntp -pl agentflow-core test   # 只跑 core 测试

# 集成测试（需 PG+Redis）：先起服务再 verify
docker compose -f docker-compose.test.yml up -d
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/agentflow mvn -s settings.xml verify
```

**重要**：
- **JaCoCo 80% 门禁**从 U1 起强制——每个有代码的模块覆盖率 < 80% → `mvn verify` 失败。空模块自动跳过。
- **CI（GitHub Actions）**：push/PR/每日 2 点触发，跑 `mvn verify` + Sonar（有 `SONAR_TOKEN` 才跑）+ Docker build（Dockerfile 在 U13 落地前跳过）。
- **本地 settings.xml 已 gitignore**——公司那边若网络直连 Central 可不传 `-s settings.xml`；若国内网络慢，自建一份镜像到 HTTPS alimaven。

## 约定（必须遵守）

- **包名**：`com.agentflow.<module-feature>`（dsl / engine / agent / observability / security / api / version / debug / checkpoint）
- **POJO**：Java records，不可变。YAML 字段用 SNAKE_CASE（`prompt_template` ↔ `promptTemplate`），ObjectMapper 配 `PropertyNamingStrategies.SNAKE_CASE` + `ACCEPT_CASE_INSENSITIVE_ENUMS`。
- **测试**：JUnit 5 + AssertJ（spring-boot-starter-test 已引入）。每个实现单元的测试覆盖 plan 的 Test scenarios（happy/edge/error/integration）。
- **覆盖率**：≥ 80%（JaCoCo INSTRUCTION，BUNDLE 维度）。
- **commit**：conventional `feat(scope): desc` / `fix(scope): desc` / `docs:` / `test:` / `refactor:`。中文描述 OK。
- **分支**：feature 分支 `feat/<unit-or-feature>`，绿了合 main（fast-forward 或 PR）。
- **plan 是决策件，不要改 plan 正文当进度**——进度靠 git commit + 本 CLAUDE.md 的"当前进度"段。计划要改走 `/ce-doc-review`。

## 怎么继续工作（接手后第一件事）

1. **读计划**：`docs/plans/agentflow/00-overview.md`（导航）→ `05-implementation-units.md`（U0–U14 全部单元的 Goal/Files/Approach/Test scenarios/Verification + Priority 矩阵）→ `03-key-technical-decisions.md`（KTD-1~9，含 v4.3 的 5 卡点决议）。
2. **确认起点**：本文件的"当前进度"段 + `git log --oneline` 看做到哪了。下一个是 U2。
3. **执行**：`/ce-work docs/plans/agentflow/00-overview.md`，按 Priority 矩阵 P0 顺序。U2/U4/U5 强依赖、都碰核心引擎，建议 serial 派发（前一个 verify 绿了再派下一个）；U6/U9 等独立单元可并行。
4. **U3 特殊**：引入 Spring AI 2.0 GA 前先确认依赖能从 Maven Central 拉到（OQ-2，2026.6.12 才 GA，可能要 bump Spring Boot 到 3.5）。拉不到先做 U4/U5。
5. **每完成一个单元**：`mvn -s settings.xml verify` 绿 → commit → 更新本 CLAUDE.md 的"当前进度"段 → 下一个。

## 关键技术决策摘要（详见 03 / 05）

- **KTD-1 BSP + 最长路径分层**：`level[v]=max(level[u])+1`，`DAGLayerer.computeSuperSteps` 已实现（U1）。
- **KTD-3 两级 Checkpoint + Recovery**：节点级（防 LLM 重复计费）+ barrier 级。Recovery 查 `nextSuperStep`（崩溃层本身）的 COMPLETED 节点。**v4.3：COMPLETED 同步写 + Semaphore(20) 限流**（U5），异步批量仅 telemetry。
- **KTD-6 AgentFunction**：`cancel()` default noop，但 **v4.3：`SpringAiAgentAdapter` 必须覆盖 cancel() 接底层 HTTP 中断**（U3）。
- **KTD-7 Spring AI 适配器**：可移植性约束——所有 Spring AI 调用收敛在适配器窄表面，2.0→2.1 迁移只碰适配器。Week-1 冒烟测试。
- **v4.3 五卡点**（已拍板，落地在各分片）：① flush 同步写+Semaphore ② 工具级授权 CallerToolAllowlist ③ `workflow_definitions` 表存旧版本 DAG ④ cancel 覆盖 ⑤ U13 解耦 demo 验收。
- **静态 DAG only**（v1）：无条件分支，留给 v2。

## GitHub

- 远程：https://github.com/Yusheng727/AgentFlow
- 默认分支：`main`
- CI secrets（按需配）：`SONAR_TOKEN`（SonarCloud）、`GITHUB_TOKEN`（自动有，GHCR push 用）

## 给接手 Claude 的提醒

- 这是**简历项目**，工程深度比功能完备更重要——每个单元要做"对"做"深"，不留半成品、不偷懒推 v1.1。
- 计划已经过两轮 6-persona 审查，**信任 plan 的决策**，不要重新质疑前提（七三开、从0复现、BSP 选型）——这些是 v4 定稿的。
- 遇到 plan 里没覆盖的实现细节，按 KTD 精神决策，记进 commit message 或本 CLAUDE.md，不要回头改 plan。
- 国内 Maven 镜像偶发"Remote host terminated the handshake"——用 HTTPS alimaven（本仓 settings.xml）即可，重试即可。
