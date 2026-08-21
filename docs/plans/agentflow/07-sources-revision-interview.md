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

---

## 档 C 面试口径（30s / 5min 自述稿，2026-08-21 定稿）

> 目的：把 2026-06-28 review 的 13 条叙事/口径 Open Question（buy-vs-build、七三开折算、BSP vs Actor/CSP、@Tool 与 InterviewCoach 边界、Spring AI 差异化、cancel noop、mock 90%、Reducer 无 demo、API Key registry、InMemory ownership、DAG size 上界、budget 阈值、R20 archetypes）收口成可直接背的口径。**主线 = "静态 DAG → 动态路由 → 迭代收敛 → 分布式解耦 → 人机协同审批 + RAG 扩展点"**，层层抛追问点、给落地答案。2026-08-21 更新：把 08-20/21 交付的 HITL 审批（U4–U6）+ R22 列加密（U7）+ demo-rag（U8）融入主线，并纠正 R21"下一步升级"的过时说法（已交付 DB 表 + 管理 API）。

### 30 秒自述（电梯版）

> "我简历三个项目各打一个维度：ToyRush 是高并发基础，InterviewCoach 是 AI Agent + RAG 应用，AgentFlow 是**后端工程化 + Agent 工程化**。AgentFlow 是我从 0 用 Java 21 写的 Multi-Agent 编排引擎——业界有 LangGraph4j、Spring AI Alibaba，但我选从 0 复现，因为我的目标是展示后端工程深度不是补生态空白。整条主线：先做静态 DAG 的 BSP 执行模型 + 两级 Checkpoint 崩溃恢复防 LLM 重复计费，再扩 v2 的动态路由和迭代循环，再 v1.1 用 Kafka 把提交和执行解耦，最后加了 Human-in-the-Loop 审批（中断→外部批准→恢复）+ RAG 扩展点实证。10 周 15 个实现单元 + 后续一批全绿，14 模块 Maven 多模块、JaCoCo 80% 门禁。"

### 5 分钟自述（深度版）

按一条主线走，每层主动交代设计取舍，把面试官要追问的点先讲掉：

**① 为什么从 0 而不扩 LangGraph4j（buy-vs-build）**
"我承认 Java 生态有 LangGraph4j 和 Spring AI Alibaba，我没有填补空白的野心。我的诉求是**用这个项目证明后端工程能力**——多线程并发模型、崩溃恢复、容错链路、可观测性、分布式解耦这些是面试想看的，不是某个框架 API 用的熟不熟。从 0 复现 BSP + Checkpoint 才有机会把我对这些问题的思考讲清楚；如果只是接 LangGraph4j，我学到的是这个框架怎么用，不是这些机制为什么这么设计。"

**② 七三开怎么折算（后端 70% + Agent 30%）**
"后端 70% 集中在引擎本体：BSP 并行执行（U2）、两级 Checkpoint + Recovery（U5）、容错链路（U4）、可观测性（U7）、Starter 封装（U13）、API 安全（U14）——这些是纯后端工程。Agent 30% 收敛在适配器窄表面上：Spring AI 和 LangChain4j 两个框架都收敛在同一个 Adapter 接口后面（KTD-7），LLM 输出 schema 校验、token 成本追踪、mock 模式。**Agent 的部分是『工程化』不是『调 prompt』**——这是和 InterviewCoach（应用级 AI）的分工点。"

**③ 为什么 BSP 而不是 Actor/CSP（KTD-1）**
"BSP 的核心是把并行计算切成同步的 barrier 超步：一层内节点并行，层间 barrier 同步合并。选它有三个理由：一是**天然消除竞态**——节点只写自己的 channel，层末 barrier 才确定性合并，不需要 Actor 那样的消息队列来管并发；二是**路劲确定**——静态 DAG 预分层后每层跑什么在运行前可算，配合 checkpoint 的路由决策重放能买回确定性；三是 Virtual Threads 友好——层内节点用 VT 并行，barrier 用 CompletableFuture.allOf 同步。Actor/CSP 在无界消息流下更强，但我们是**有界 DAG 编排**，BSP 更简单直接。代价是并行度被 barrier 限制，但对 Multi-Agent 工作流（节点数几十）这个并行度足够。"

**④ 两级 Checkpoint 为什么能防 LLM 重复计费（KTD-3）**
"崩溃恢复最怕两件事：丢进度、或者重复执行导致 LLM 重复计费。我做**节点级 checkpoint**——每个节点执行完立刻把 channel 输出落库（COMPLETED 状态 + token 计数），恢复时只重跑崩溃当层还没完成的那几个节点，已完成的直接复用输出，不重新调 LLM；再配合 **barrier 级 checkpoint** 记录层间合并结果。RecoveryProtocol 查崩溃层本身的 COMPLETED 节点，天然避免 off-by-one 重跑整层。这是最重的后端工程点——一个崩溃恢复的路径，要同时保进度、防双计费、防 stray 恢复。"

**⑤ v1.1 Kafka 提交/执行解耦（分布式语义）**
"v1 是本地 VT 异步执行，v1.1 我把提交和执行经 Kafka 解耦——REST submit 只往 topic 派发消息，消费者拉取后执行。关键设计是**幂等**：消费者收到消息先查 checkpoint 终态，已 SUCCESS/FAILED 的跳过——这样 at-least-once 重放不会重复计费。我这次 review 还被抓到一个真 bug：retry 走 Kafka 时 FAILED 是终态，被幂等跳过吞掉了，重试永远不生效——本地 dispatcher 直跑 run() 所以单测全绿，是典型的『测试绿 ≠ 生产生效』。"

**⑥ v2 动态路由 + 循环（把静态 DAG 讲活）**
"v1 只做静态 DAG，面试官最sharp 的问题就是『动态路由怎么办？』。v2 我扩成**运行时条件路由**：`when` 谓词选边、`on_error` 失败兜底；执行模型上不用放弃 BSP，而是**静态分层 + 每层可达性剪枝**——被路由切断的下游标 SKIPPED，checkpoint 只持久化路由决策。再往上扩**循环/回边**：迭代轮次 + 双 active 集合，收敛或超限都会终止。这两步的回答都是同一个：『动态路由牺牲执行路径确定性，我用 checkpoint 路由决策重放把它买回来』。"

**⑦ @Tool 与 InterviewCoach 的边界**
"InterviewCoach 是应用级 Agent——它用工具解决面试问答这个具体场景；AgentFlow 是**框架级工具布线**——@Tool 怎么从 bean 反射注册进 ToolSpecification、怎么在 YAML 解析期按 caller 做工具级授权、工具调用循环怎么防死循环（≤5 轮）。层不一样：一个是『用工具达到业务目的』，一个是『把工具调用的机制做对』。这叫互补不重复。"

**⑧ 安全（R21/R22）**
"三位一体：X-API-Key 鉴权（SHA-256 存 hash）+ 所有权校验防 IDOR + 工具级授权——`CallerToolAllowlist` 按 caller 白名单校验 YAML 里能引用哪些 @Tool，防止有人提交 YAML 引用特权工具。R21 已升级成 DB 表 + 管理 API（`ToolGrantRepository` InMemory/Jdbc + V6 迁移 + `ToolGrantController`：grant/revoke 仅 admin key 门控），配置硬编码和 DB 求和、总空才 allow-all。R22 是**列级静态加密**：checkpoint 敏感列（node output / channel / 审批载荷）落库前用 AES-256-GCM 加密——`AESGCM:` 自描述前缀 + legacy 明文行兼容（升级前数据读得动），生产 `fromEnvStrict()` fail-closed（缺 key 拒绝明文落地）。凭证只从 env 读、禁止硬编码；prompt/trace 走脱敏。"

**⑪ HITL 审批（U4–U6，人机协同）**
"把工作流从'全自动'扩到'可中断等人工'：节点抛 `ApprovalRequiredException` → 引擎在 super-step barrier 识别、把**上下文快照**（含兄弟输出、不含审批节点）随审批单落库 → 置 `AWAITING_APPROVAL` 暂停 → 外部 REST 批准后 `approveAndResume` 重跑待批节点注入决策、续跑下游。三个点最能打：一是**防重复计费**——快照只含兄弟输出，恢复时兄弟不重跑，只重跑待批节点（KTD-3 在 HITL 的镜像）；二是**口径安全**——`decidedBy` 服务端从 caller 的 X-API-Key hash 推导，客户端传的 decidedBy 一律忽略，防审批身份伪造；三是**三种恢复路径统一**——崩溃恢复、审批恢复、retry 都复用 `runRounds` 同一套骨架，checkpoint 路由决策重放买回确定性。待批列表返回精简投影，requestPayload/contextSnapshot 存库不下发。"

**⑫ RAG 扩展点（U8，KTD-6 实证）**
"InterviewCoach 是应用级 RAG，AgentFlow 证明的是**编排引擎的 Agent 扩展点成立**：`RagAgentFunction` 在 Agent 侧做'检索→增强→委托'（`InMemoryVectorStore` 用确定性 token embedder，离线无外部向量库），`RagEngineZeroChangeTest` 直接拿 BspEngine 跑 `agent: rag` 节点，引擎/DSL 零改动就成功——说明 RAG 是 Agent 内部实现细节，引擎能编排任何 AgentFunction。这验证了架构的核心承诺：扩展点靠契约成立，不靠白盒 hack。"

**⑨ mock 模式怎么控制成本（R13）**
"90% 开发期用零成本 mock——`MockAgentFunction` 从 YAML 的 `mock_response` 读预设响应、支持 `${channel}` 占位符引用上游，语义上等价但不发真实 LLM。只有档 1 端到端才接真实 DeepSeek（OpenAI 兼容，env 读 key）。这让我每轮 CI 都能绿、开发迭代快，Demo 时才烧真钱。"

**⑩ 可观测性**
"ExecutionTrace 树记每节点结果 + Micrometer 5 类指标（执行/延迟/token/成本/预算超限）+ Grafana 面板 + 成本核算表。崩溃了能查 trace，跑贵了能看成本面板。"

**收尾（简短总结）**
"一句话收：AgentFlow 证明的是『我能把一个分布式系统面对的那些后端难题——并发、崩溃恢复、容错、安全、可观测、分布式解耦——在真实工程里做对做完』。"

← 返回 [`00-overview.md`](./00-overview.md)
