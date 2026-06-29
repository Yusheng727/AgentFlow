# AgentFlow

> Java 原生轻量级 Multi-Agent 编排引擎 · YAML DSL · BSP 执行模型 · 两级 Checkpoint + Recovery

AgentFlow 用 YAML 声明多 Agent 工作流，按 BSP（Bulk Synchronous Parallel）模型把 DAG 分层为 super-steps，节点级 + barrier 级两级 Checkpoint 保证崩溃恢复不重复调用 LLM，Java 21 Virtual Threads 并行执行。v1 聚焦静态 DAG + PostgreSQL 持久化 + 调试体验。

## 为什么造这个

这不是"填补 Java 生态空白"的项目——[LangGraph4j](https://github.combservices/langgraph4j)、Spring AI Alibaba 等已存在。本项目**从0复现**编排引擎核心（BSP / 两级 Checkpoint / 容错链路 / DSL / 可观测），借此展示后端工程深度。详见 [`docs/plans/agentflow/01-problem-frame.md`](docs/plans/agentflow/01-problem-frame.md)。

## 现状

v1 进行中（15 个实现单元 U0–U14，10 周计划）：

- ✅ U0 CI/CD（GitHub Actions + JaCoCo 80% 门禁 + SonarQube + docker-compose 测试环境）
- ✅ U1 YAML DSL（Jackson 解析 + 三层校验 + 最长路径分层）
- 🚧 U2 BSP 执行引擎核心（进行中）
- ⏳ U3–U14（Agent 适配器 / 容错 / Checkpoint / 调试 / 可观测 / 版本管理 / Mock / 3 个 Demo / Starter / 安全）

完整计划见 [`docs/plans/agentflow/`](docs/plans/agentflow/)。

## 模块

| 模块 | 职责 |
|:---|:---|
| `agentflow-core` | BSP 引擎 / DSL / Checkpoint / 容错 / 可观测 / 安全 |
| `agentflow-adapters/spring-ai` | SpringAiAgentAdapter + Advisor Chain + OutputSchemaValidator |
| `agentflow-api` | REST 端点 + 鉴权（API Key + ownership + 工具级授权） |
| `agentflow-starter` | `@EnableAgentFlow` + AutoConfiguration |

## 快速开始

```bash
# JDK 21 + Maven 3.9+
mvn -B -ntp verify          # 编译 + 测试 + JaCoCo 80% 门禁
```

集成测试需 PostgreSQL + Redis：

```bash
docker compose -f docker-compose.test.yml up -d
mvn -B -ntp verify
```

## 技术栈

Java 21（Virtual Threads）· Spring Boot 3.4 · Spring AI 2.0 · PostgreSQL · Redis · Maven 多模块 · JUnit 5 + AssertJ + JaCoCo · GitHub Actions + SonarQube

## 文档

- [计划（8 分片）](docs/plans/agentflow/00-overview.md) — Problem Frame / Requirements / KTD / 设计 / 实现单元 / Open Questions
- [CLAUDE.md](CLAUDE.md) — 接手指南（给 Claude Code）
- [架构图 + ER + 状态机](docs/plans/agentflow/04-high-level-design.md)

## License

MIT
