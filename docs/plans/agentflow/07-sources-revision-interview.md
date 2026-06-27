## Sources & Research

- [Spring AI 2.0.0 GA Release](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/) — 2026.6.12
- [Spring AI Agentic Patterns（5 种基础模式）](https://spring.io/blog/2025/01/21/spring-ai-agentic-patterns)
- [Spring AI Subagent Orchestration](https://spring.io/blog/2026/01/27/spring-ai-agentic-patterns-4-task-subagents)
- [LangGraph Graph API（BSP 模型参考）](https://docs.langchain.com/oss/python/langgraph/graph-api)
- [LangGraph Persistence（Checkpoint 机制）](https://langchain-ai.github.io/langgraph/concepts/persistence/)
- [LangGraph Error Handling（三层容错）](https://langchain-ai.github.io/langgraph/concepts/error_handling/)
- [spring-ai-community/spring-ai-agent-utils](https://github.com/spring-ai-community/spring-ai-agent-utils) — 社区工具集
- [Redisson 官方文档](https://redisson.org/docs/)
- [Spring Kafka 参考文档](https://docs.spring.io/spring-kafka/reference/)
- [CNCF Serverless Workflow DSL](https://github.com/serverlessworkflow/specification)
- [Anthropic: Building Effective Agents](https://www.anthropic.com/research/building-effective-agents) — 理论基础

---

## Revision History

| 日期 | 修订版本 | 修订内容 | 触发原因 |
|:---|:---:|:---|:---|
| 2026-06-25 | v1 | 计划 v1 初稿 | 首次规划 |
| 2026-06-25 | v2 | **大修订**：BSP 算法明确化、Checkpoint 重构、场景差异化、范围削减、新增 5 项 requirement | 4 位审查者（架构师、工程师、产品经理、风险专家）反馈 |
| 2026-06-25 | **v3** | **完整修订**：<br>🔴 **C1**: Checkpoint 恢复逻辑增加 status=COMPLETED 强制过滤（伪代码）<br>🔴 **C3**: U5 补充完整 Recovery Protocol 伪代码（含 4 步算法）<br>🔴 **C4**: R7 从"Supervisor 模式"改为"轻量 Supervisor"，明确是普通 AgentFunction<br>🟠 **I1**: U3 新增 LLM 输出 Schema 校验（OutputSchemaValidator + 结构化 output）<br>🟠 **I2**: U5 补充 VT+PG 并发写入缓解方案（异步队列 + 批量 flush）<br>🟠 **I4**: U0 新增 GitHub Actions CI 搭建任务（Week 1 Day 1）<br>🟠 **I7**: 新增"Interview Value"章节（9 个面试追问点） | Senior Architect Review 反馈 |
| 2026-06-25 | **v4** | **方向重确认 + 真 bug 修复**（ce-doc-review 6 persona 审查后）：<br>🟢 **方向**：确认 AgentFlow 不转向——七三开（后端 70% + Agent 30%）+ 主流技术栈 + 从0复现展示后端能力；与 ToyRush/InterviewCoach 三项目互补<br>🔴 **P0-1 Recovery off-by-one bug**：伪代码 `targetSuperStep-1` 查错层，改为查询 `nextSuperStep`（崩溃层本身），修复 LLM 重复计费<br>🔴 **P0-2 super-step 编号统一为 0-based**：分层算法/时序图/checkpoint 表/Recovery 全部统一<br>🔴 **P0-3/4 安全**：新增 R21（API 鉴权防 IDOR）+ R22（LLM 凭证管理 + 敏感数据脱敏）<br>🔴 **P0-5 前提重写**：Problem Frame 从"Java 生态没有同类产品"改为"从0复现展示后端工程能力"，补诚实声明 + LangGraph4j/Spring AI Alibaba 对比<br>🟠 **P1**：U12 super-step 数 5→4 修正；Interview Value 重写为七三开叙事<br>⚪ **safe_auto**：U3 重复 Test scenarios 删除、覆盖率 80% 统一、Phase 1 Week 1-5、表名统一、单元数 13→14、关键路径补 U11/U12 | ce-doc-review 6 persona（coherence/feasibility/scope/security/adversarial/product）审查 |
| 2026-06-25 | **v4.1** | **启动前最小修补**（交叉评判另一 AI 审查总结后）：<br>🔴 **R21/R22 落地**：新增 U14（API 鉴权 + 凭证管理 + POST /workflows Controller，Week 5-6），含 ApiKeyAuthFilter/WorkflowOwnershipChecker/CredentialManager/PromptRedactionFilter；修复 v4"需求层有、实现层悬空"缺口<br>🔴 **POST /workflows Controller 归属**：U14 承接 WorkflowController（提交/状态/重试入口），修复状态机提及但无 U 承接<br>🟠 **Success Metrics 矛盾**：移除"800 行 vs 50 行 90%+"指标（与 v4 Problem Frame 矛盾且基线无来源），改为"后端工程深度展示（15 单元覆盖七大维度）"<br>⚪ **同步**：单元数 14→15、关键路径补 U14、Success Metrics 维度 6→7（加安全） | 交叉评判另一 AI 的 v4 审查总结（去误判 + 补漏判） |
| 2026-06-28 | **v4.2** | **两轮 ce-doc-review 闭环**（6 persona × 2 round）：<br>🔴 **R1（14 条 Apply）**：U14 移出 Go/No-Go 砍单清单；ApiKeyAuthFilter WebFlux→servlet OncePerRequestFilter；KTD-5 Redis 范围澄清；U5 flush-then-barrier 不变式；U4 失败传播 FAILED abort；U3 SpEL 定界符 `#{}`→`${}`；U14 归位 Phase 2；ER 图补 created_by/workflow_version；Unit Priority 矩阵（U0-U14 P0/P1/P2）；KTD-7 可移植性约束 + Week-1 冒烟；OQ-1 Spike 扩端到端；U3 测试 TransientError→TransientException<br>🔴 **R2（9 条 Apply）**：U5 ON CONFLICT→upsert（FAILED→COMPLETED 升级）；ExecutionTrace.java 移自 U7→U3；POST /workflows 真异步化；LoggingAdvisor.java 进 U3 Files；U6 DryRunEngine 用 core 本地 mock（消除反向依赖）；U3 retry 预算组合封顶 9x；U14 加 V2__add_created_by.sql；R21 端点清单补 /status、/retry + 测试<br>🟠 **18 条 Defer 进 Open Questions**（`### From 2026-06-28 review`）：pre-barrier flush、U13 依赖 U12、工具级授权、从0复现前提、InMemory ownership、API Key registry、R20 无单元、七三开、U14/U7 排期、Reducer 无 demo、BSP 理由稻草人、90% mock、Spring AI 2.1 差异化、DAG size 上限、budget 阈值、R14 定义存储、cancel noop、@Tool 重叠 | ce-doc-review Round 1 + Round 2 |
| 2026-06-28 | **v4.3** | **动工前 5 卡点拍板**（用户决策，全采纳推荐方案）：<br>🔴 **卡点1 flush 窗口**：U5 COMPLETED 节点输出改同步写 + Semaphore(max=20) 限流，异步批量仅留 telemetry，R3 字面成立<br>🔴 **卡点2 工具级授权**：新增 CallerToolAllowlist（per-caller，config 硬编码），R21 补工具级授权，YAML 解析期校验 @Tool<br>🔴 **卡点3 R14 定义存储**：新增 workflow_definitions 表 + WorkflowDefinitionStore + V3 迁移，Recovery/retry 按 version 取定义，不从 classpath 读<br>🔴 **卡点4 cancel 强制**：SpringAiAgentAdapter 覆盖 cancel() 接底层 HTTP 中断，KTD-6 加覆盖约束，生产 profile 启动校验<br>🔴 **卡点5 U13 解耦**：U13 Dependencies 改 U2/U3/U5/U14，验收改"≥1 Demo（U10）跑通 + Starter/Docker 一键拉起"，3-Demo 降 stretch，P0 不依赖 P1 demo<br>⚪ **Open Questions**：5 条 ⚠️ 动工前必答全部 ✅ v4.3 已解决，剩 13 条演示前定即可 | 用户拍板 5 卡点（全采纳推荐） |

## Interview Value（面试价值分析，v4 重写为七三开叙事）

**简历定位**：本项目与 ToyRush（高并发基础）、InterviewCoach（AI Agent+RAG 应用）形成三项目互补。AgentFlow 承担**后端工程能力（70%）+ Agent 工程化（30%）**展示，深化后端深度，不与 InterviewCoach 的 AI 能力维度重复。

| 维度 | 模块 | 面试追问点 |
|:---|:---|:---|
| **后端 70%** | **KTD-1: BSP 模型** | "为什么选 BSP 而不是 Actor Model？" → 天然避免竞态、确定性合并、Virtual Threads 友好 |
| 后端 | **U2: BSP 执行引擎** | "并行节点写入冲突怎么处理？" → Reducer 机制（overwrite/concat/max）、确定性保证 |
| 后端 | **KTD-3 + U5: 两级 Checkpoint + Recovery** | "引擎崩溃如何恢复？LLM 重复计费怎么办？" → 节点级 checkpoint 防重复计费 + Recovery Protocol（nextSuperStep 查询崩溃层 COMPLETED 节点复用） |
| 后端 | **U4: 容错链路** | "异常合约怎么设计？Transient vs Fatal 怎么区分？" → 分层异常 + Timeout/Retry/ErrorHandler 三层 |
| 后端 | **U1: DSL + DAG 校验** | "DSL 怎么设计才不易写错？" → 三层校验（Jackson 类型 → 语义 → DAG 完整性）+ JSON Schema IDE 支持 |
| 后端 | **U7: 可观测性** | "怎么排查工作流跑错？" → ExecutionTrace 树 + Micrometer 指标 + Grafana + 成本核算 |
| 后端 | **U13: Starter 封装** | "别人怎么用？" → Spring Boot Starter + @EnableAgentFlow + Docker Compose 一键部署 |
| **Agent 30%** | **KTD-7: Spring AI 集成** | "为什么用 Spring AI 而不是 LangChain4j？" → Advisor Chain 深度整合、@Tool 自动注册、token 成本追踪 |
| Agent | **U3: OutputSchemaValidator** | "LLM 输出不符 schema 怎么办？" → JSON Schema 校验 + 带反馈重试 + structuredOutput |
| Agent | **R13: Mock 模式** | "LLM 调用成本怎么控制？" → 90% 开发时间用 mock，仅 Demo 调真实 API |
| Agent | **R14: 版本管理** | "生产环境如何安全更新工作流定义了怎么处理？" → SemVer + 运行时隔离不同版本实例 |
| **叙事** | **Problem Frame** | "LangGraph4j 已存在，你为什么从0造？" → 不追求填补空白，从0复现 BSP+Checkpoint+容错展示后端工程深度；与 InterviewCoach（AI 能力）互补 |

**面试故事线（1 分钟版本，v4 重写）：**
"我简历三个项目分别打不同维度：ToyRush 展示高并发基础，InterviewCoach 展示 AI Agent+RAG 应用能力，AgentFlow 展示后端工程化 + Agent 工程化。AgentFlow 是我从0用 Java 实现的 Multi-Agent 编排引擎——业界有 LangGraph4j、Spring AI Alibaba 等方案，但我选择从0复现 BSP 执行模型 + 两级 Checkpoint + 状态机 + DSL，借此展示并发模型设计、崩溃恢复、容错链路、可观测性这些后端工程深度。它和 InterviewCoach 形成'AI 能力 + 后端工程能力'的互补。项目 10 周内完成，15 个实现单元（U0-U14），Week 5 Go/No-Go Gate 后专注 Demo 和文档。"

← 返回 [`00-overview.md`](./00-overview.md)
