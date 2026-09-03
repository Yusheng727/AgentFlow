## Problem Frame

### 为什么做 AgentFlow

**这不是"填补生态空白"的项目，而是"从0复现展示后端工程能力"的项目。**

Multi-Agent 编排是 AI 落地的核心工程问题——多个专业 Agent 如何并行执行、状态如何持久化、失败如何恢复、成本如何追踪。业界已有成熟方案（LangGraph4j、Spring AI Alibaba Agent Framework、Spring AI 2.0 原生 Agentic Patterns），但我选择**从0用 Java 重新实现一个轻量级编排引擎**，借此展示后端工程能力：并发模型设计（BSP）、状态持久化与崩溃恢复（两级 Checkpoint + Recovery Protocol）、容错链路（Timeout/Retry/ErrorHandler）、DSL 设计与校验、可观测性、Spring Boot Starter 封装。

### 项目定位（工程深度优先）

本项目以**从0复现展示后端工程能力**为目标，重点在后端工程化 + Agent 工程化：

| 能力面 | 占比 | 覆盖 |
|:---|:---|:---|
| **后端工程化** | 约 70% | BSP 执行模型 / Checkpoint 与崩溃恢复 / 容错链路 / 可观测性 / DSL |
| **Agent 工程化** | 约 30% | Spring AI / LangChain4j 集成、Schema 校验、Mock 模式、RAG 扩展点 |

### 现有方案对比（诚实版）

| 方案 | 语言 | 编排模型 | Spring 集成 | 备注 |
|:---|:---|:---|:---:|:---|
| LangGraph4j | Java | StateGraph+BSP+Checkpoint | 可集成 | LangGraph 的 Java 移植，1.7k stars，活跃 |
| Spring AI Alibaba | Java | Sequential/Parallel/Routing/Loop+Subagent | ✅ 原生 | 9.7k stars，体量最大，绑阿里云生态 |
| Spring AI 2.0 原生 | Java | 5 种 Agentic Pattern + Subagent | ✅ 原生 | 官方，Parallelization Workflow 已覆盖"并行→汇总" |
| LangGraph | Python | StateGraph+BSP | ❌ | Python 生态，不能嵌入 Java 后端 |
| **AgentFlow（本项目）** | **Java** | **DAG+BSP+两级Checkpoint** | **✅** | **从0复现，展示后端工程能力，非填补空白** |

> **诚实声明**：AgentFlow 不声称"Java 生态没有同类产品"——LangGraph4j 等已存在。本项目的价值在于**从0实现编排引擎核心**所展示的后端工程深度，而非生态空白填补。对外叙事以此为准。

### 目标用户

Java 后端开发者。已在用 Spring AI 2.0 做单 Agent 应用，需要一个方案把多个专业 Agent 编排起来，不想引入 Python sidecar 或重量级框架。

← 返回 [`00-overview.md`](./00-overview.md)
