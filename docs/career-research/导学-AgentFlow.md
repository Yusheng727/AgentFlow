# 导学-AgentFlow：零基础到面试可讲的完整路径

> 产出：2026-09-02（/project-guide 规范）｜ 目标读者：本人（项目由 AI 辅助开发、本人零基础需内化）
> 求职方向：Java 后端 + AI 应用（Agent 方向），对应简历 A 版（agent）/ B 版（后端）
> 本文是**单入口导学**：告诉你按什么顺序读哪些已有材料、每份材料在面试准备中怎么用、按天怎么排。不抄写已有内容，只给路径。

## 一、先读这里：三分钟建立全局观

AgentFlow 是你简历三项目中的「后端工程深度」担当：Java 21 从 0 实现的 Multi-Agent 编排引擎。一句话讲它做什么——

> 用户提交一份 YAML 声明的工作流 DAG → 引擎按 BSP（整体同步并行）模型分层并行执行每个节点（节点背后是 LLM 调用）→ 全程 checkpoint 持久化、崩溃可恢复 → 提交与执行经 Kafka 解耦 → 支持人工审批中断/恢复、动态路由、循环、列级加密。

**设计哲学一个词：确定性买回。** 动态路由牺牲了执行路径确定性 → checkpoint 只存「已走的边」恢复期重放买回；并发牺牲确定性 → barrier 按声明序合并买回；审批暂停 → 上下文快照买回。面试的元叙事就这一句（详见《面试问题覆盖地图》「我理解到的设计思想」节）。

**简历数字口径**（背熟，不报精确数）：500+ 测试 / JaCoCo 80% 门禁 / 13 Maven 模块 / 170+ commits / CI 全绿 / 真 PG·真 Kafka·真实 DeepSeek 三环境验证。

## 二、前置知识（面试高频标注）

| 知识点 | 为何需要 | 在本项目中的位置 | 高频度 |
|---|---|---|---|
| BSP（整体同步并行） | 引擎的执行模型，几乎所有问题都从这里发散 | `agentflow-core/.../engine/BspEngine.java`；人话版见 08 交涉指南 §一 | ★★★ |
| Checkpoint / 崩溃恢复 | 防 LLM 重复计费是核心卖点，两级设计是必考点 | `engine/checkpoint/RecoveryProtocol.java`；08 §二 | ★★★ |
| at-least-once 与幂等 | Kafka 解耦的立身之本，后端岗必考 | `agentflow-kafka-starter/.../KafkaWorkflowConsumer.java`；08 §四 | ★★★ |
| HITL（人工在环） | A 版简历 JD 逐字命中点（任务中断/敏感操作确认） | `BspEngine#approveAndResume`；08 §三 | ★★☆（Agent 岗★★★） |
| AES-256-GCM / fail-closed | 安全横切的两个高频词 | `core/security/ColumnEncryptors.java`；08 §五 | ★★☆ |
| RAG（检索增强生成） | Agent 岗必问；本项目定位是「扩展点实证」 | `demo-rag`；08 §六 | ★★☆（Agent 岗★★★） |
| Virtual Threads（Java 21） | 层内并行的载体，「为什么不怕阻塞」会追问 | 08 §一 Q2 | ★★☆ |
| JaCoCo / 覆盖率门禁 | 质量工程数字的解释权 | 08 §七 | ★☆☆ |

> 前置知识的人话版+问法×答法**全部在 `docs/career-research/08-agentflow-interview-negotiation.md`**，本文不重复——导学告诉你读什么，08 教你怎么说。

## 三、重点亮点与学习顺序（先看这个）

| 亮点标题 | 为什么重要 | 通用技术关键词 | 先看哪些文件 | 建议学习顺序 |
|---|---|---|---|---|
| 并发模型（BSP 执行引擎） | 两版简历第一条，一切追问的源头 | BSP、barrier、免锁、确定性 | 导学→ walkthrough「bsp-engine-execution-loop」→ 08 §一 | 1 |
| 容错与崩溃恢复 | 防 LLM 重复计费 = 钱的问题，最有说服力 | checkpoint、幂等重放、off-by-one | walkthrough「checkpoint-recovery-protocol」→ 08 §二 | 2 |
| 异步解耦与幂等（Kafka） | 后端岗分布式关键词；「retry 被吞」是最佳故事 | at-least-once、条件 UPDATE、dead letter 边界 | walkthrough「kafka-dispatch-decoupling」→ 08 §四 | 3 |
| 人机协同（HITL 审批） | Agent 岗 JD 逐字命中；三恢复路径统一 | 状态机、快照、原子幂等决策 | walkthrough「hitl-approval-lifecycle」→ 08 §三 | 4 |
| 可扩展编排（双框架 + RAG） | 「为什么不用现成的」必问题；构建级证明少见 | 窄接口、适配器、扩展点契约 | walkthrough「dual-adapter-comparison」+「rag-extension-point」→ 08 §六 | 5 |
| 安全横切与可观测 | 收尾防线的广度展示 | 纵深防御、静态加密、指标族 | walkthrough「api-security-layers」→ 08 §五/§八 | 6 |

## 四、必备知识点 checklist

- [ ] 能不假思索说出 BSP 三句话：同层 VT 并行 / allOf barrier / 声明序 Reducer 合并
- [ ] 能画出提交→执行的调用链草图（见 system-overview.md 的 8 跳主链）
- [ ] 能讲出两个 P0 修复故事：恢复 off-by-one、stray COMPLETED 防护（各自「错在哪→为什么错→怎么修」）
- [ ] 能讲出「retry 被幂等吞掉」故事并总结出「测试绿 ≠ 生产生效」
- [ ] 能一句话回答「为什么不用 LangGraph4j」（档 C ①，30 秒版在 07 号文档）
- [ ] 能背出扩列判据：「列内是否含业务敏感数据」+ 5 列清单（08 §五 Q2）
- [ ] 两个已知边界主动说：恢复无生产调度器 / Kafka 单 JVM 语义（08 §二 Q3、§四 Q7）
- [ ] 数字口径统一：500+ / 80% / 13 / 170+，不报精确数

## 五、推荐阅读（结合仓库）

| 主题 | 通用技术点 | 建议阅读位置 | 预计时间 | 读完能回答什么 |
|---|---|---|---|---|
| 全景与模块关系 | 架构分层、调用链 | `docs/learning/Agentflow-code/system-overview.md` + `module-map.md` | 40min | 系统怎么组成、一条请求经过哪几层 |
| 执行模型 | BSP/barrier/Reducer | `docs/learning/Agentflow-code/implementation-walkthroughs/bsp-engine-execution-loop.md` | 60min | 为什么 BSP、动态路由/循环怎么共存、重试三层预算 |
| 崩溃恢复 | checkpoint/幂等重放 | 同目录 `checkpoint-recovery-protocol.md` | 60min | 两级为什么、恢复五步、两个 P0 故事 |
| Kafka 解耦 | at-least-once/幂等 | 同目录 `kafka-dispatch-decoupling.md` | 45min | 条件认领、earliest、retry bug 全貌 |
| HITL | 状态机/快照 | 同目录 `hitl-approval-lifecycle.md` | 45min | 暂停怎么实现、快照为何只含兄弟输出、并发审批 |
| 双框架与 RAG | 窄接口/扩展点 | 同目录 `dual-adapter-comparison.md` + `rag-extension-point.md` | 50min | 构建级隔离怎么证明、RAG 为什么零改动 |
| 安全纵深 | 认证/授权/加密 | 同目录 `api-security-layers.md` | 50min | 五层各防什么攻击、fail-closed 双工厂 |
| 设计取舍 | ADR 方法论 | 同目录 `technical-decisions/` 三篇（bsp-over-actor / minimal-fact-checkpoint / zero-framework-core） | 40min | 备选方案比较怎么说才可信 |
| 工程思想 | 元叙事 | 同目录 `learning-summaries/backend-engineering-philosophy.md` | 30min | 「确定性买回」四例同构 |
| 面试弹药地图 | 追问链→证据 | 同目录 `learning-summaries/interview-question-coverage-map.md` | 20min | 每条追问链去哪找证据 |
| 口述稿 | STAR/口径 | `docs/plans/agentflow/07-sources-revision-interview.md` 档 C 节（30s/5min 自述 + 13 条追问） | 40min | 自我介绍、buy-vs-build、为什么不接 Spring Cloud |
| 交涉话术 | 概念人话+兜底 | `docs/career-research/08-agentflow-interview-negotiation.md` | 40min | 每个概念 30 秒人话版 + 答不上兜底 |
| 主张审计 | 什么能说什么不能 | `docs/career-research/00-agentflow-highlights-asu.md` §五 | 20min | 每条简历 bullet 的边界与风险 |
| 业务规则速查 | 流程级事实 | `docs/business/flows/`（11 份，按需查）+ `docs/business/glossary.md` | 按需 | 某条规则的确切行为（如审批优先级） |

## 六、自学提醒

- 本导学负责学习路径与题目，不提供逐行讲解——若某文件或原理看不懂，请继续追问 AI（把具体文件+你的困惑点直接丢过来即可）。
- 零基础最大陷阱是「读懂了 = 会讲了」。每个概念读完必须**合上材料自己复述一遍 30 秒人话版**，复述不出来的回炉。08 交涉指南就是为此准备的跟读材料。

## 七、项目技术定位

**后端工程化 70% + Agent 工程化 30%（交叉方向）**。依据：引擎本体（BSP/checkpoint/容错/Kafka/安全/可观测）是纯后端工程；Agent 部分收敛在适配器窄表面（框架集成/schema 校验/成本追踪/RAG 扩展点），是「工程化」不是「调 prompt」——与 InterviewCoach（应用级 AI）明确分工互补。这个七三开叙事的标准口径在档 C ②。

## 八、核心原理解析（问题 → 机制 → 在本项目中的落点）

1. **问题：并行节点互不感知，怎么不锁而正确？** → 机制：单写者结构化消除竞态 → 落点：层内只读快照互不可见，全局写只在 barrier 后单线程段按声明序合并（walkthrough bsp §2）。
2. **问题：崩溃后如何既不丢进度又不重复花钱？** → 机制：最小事实持久化 + 恢复期重算派生态 → 落点：checkpoint 只存三类事实（节点输出/barrier/已走边），可达集等恢复期 BFS 重算（ADR minimal-fact-checkpoint）。
3. **问题：动态路由破坏确定性怎么办？** → 机制：把不可重来的副作用转成可重放事实 → 落点：checkpoint 只持久化已走边，恢复期重放（这是「确定性买回」最典型的例子）。
4. **问题：多框架共存怎么不互相污染？** → 机制：窄接口 + 构建级隔离 + 框架知识外移 → 落点：core 零 LLM 框架依赖，异常分类器组合注入（ADR zero-framework-core）。
5. **问题：重复投递怎么不重复执行？** → 机制：幂等做在业务状态机条件转移上 → 落点：tryClaim 单条 UPDATE PENDING→RUNNING 恰一胜出，对本地/Kafka/未来派发统一生效。

## 九、关键设计决策

| 备选 | 取舍 | 风险 | 验证 |
|---|---|---|---|
| BSP vs Actor/CSP | 有界 DAG 不需要消息队列管并发；换免锁+可测 | 并行度受层内限制 | ADR bsp-over-actor 有完整比较 |
| Checkpoint 存最小事实 vs 存全状态 | 派生态重算，减少崩溃窗口不一致 | 恢复期计算成本 | Recovery 测试 + 真 PG IT |
| 从 0 复现 vs 扩 LangGraph4j | 价值在过程非填补空白 | 被质疑重复造轮子 | 档 C ① 30 秒标准答案 |
| Kafka 单 JVM 语义 | 先解耦模块边界，跨节点后置 | send 无 outbox 悬空 PENDING | 已知边界，主动交代（08 §四 Q7） |
| 不接 Spring Cloud | 赛道共识是无状态执行层+队列+共享存储 | 「你 QPS 多少」无法回答时不上注册中心 | 档 C ⑬ 完整调研口径 |

## 十、量化与验证（含待测，建议）

- 建议面试前实测 `git rev-list --count HEAD` 与 `mvn verify` 输出，确保口头数字与仓库现状一致。
- 建议给自己录一遍 30s 自述（档 C），回听检查有没有「嗯那个就是说」类填充词。
- 待测：无压测数据——被问性能一律答「未做系统压测，有单元级并发正确性断言」，不编造。

## 十一、按天计划（零基础 → 面试可讲，共 7 天）

> 前提假设：每天 2-3 小时。原则：**前 4 天建理解，后 3 天练口播**。每天结束做一次「合上材料复述」。

| 天 | 任务 | 读什么 | 产出检验 |
|---|---|---|---|
| D1 | 全局观 + 并发模型 | 本文 §一二三 → system-overview + module-map → bsp walkthrough | 能画三层架构图；BSP 三句话脱口而出 |
| D2 | 容错与恢复 | checkpoint-recovery-protocol walkthrough → 08 §二 | 两个 P0 故事能白话讲清 |
| D3 | Kafka + HITL | kafka-dispatch-decoupling + hitl-approval-lifecycle walkthroughs → 08 §三四 | retry 故事 + 快照镜像设计能讲 |
| D4 | 双框架/RAG/安全/可观测 | dual-adapter + rag-extension-point + api-security-layers walkthroughs → 08 §五六八 | 扩列判据背熟；RAG 定位（demo 级实证）说清 |
| D5 | 设计决策 + 元叙事 | technical-decisions 三篇 ADR + backend-engineering-philosophy | 「确定性买回」四例能连着讲 |
| D6 | 口述稿内化 | 档 C 30s/5min 自述 + 13 条追问 → 对照 08 逐条跟读 | 30s 自述不看稿讲顺；13 追问各自 30 秒能接 |
| D7 | 全真模拟 | interview-question-coverage-map 七条追问链自测 → 缺哪补哪 | 每条链被随机追问 2 层能接住 |

**面试前一晚**：08 交涉指南 §十 checklist 逐项过。
**做完 7 天还想更深**：按 backend-engineering-philosophy 结尾的三个候选（observability 链路 / demo-api 装配 / UI 数据流）继续深挖。
