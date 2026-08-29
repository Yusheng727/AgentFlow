# AgentFlow 亮点酥化成稿（/asu 产出）

> 成稿时间：2026-08-29 ｜ 输入：`docs/` 全量文档（plans/business/learning/developer-notes/career-research/简历 PDF）+ `claim-ledger.json` 主张—证据账本
> 用途：从全部文档里蒸馏出**最值得写进简历的亮点**，按「问题→方案→结果」闭环改写，每条标注证据位置；并维护 30s 口述版与书面版两个粒度。
> 兄弟文档：`04-resume-draft-and-strategy.md`（成稿模板+投递策略）。本文档是它的高效素材来源——先从这里挑 bullet，再到 04 组装最终版面。

## 一、一句话岗位定位（两版）

- **AI 应用 / Agent 方向**：Joel 式「Agent 工程化」——不是调 prompt，是把多 Agent 协作的中断/恢复/计费/权限/审批这些工程难题，在一个从 0 实现的编排引擎里做对做完。
- **Java 后端方向**：自研 Multi-Agent 编排引擎，展示并发模型、崩溃恢复、分布式解耦、安全横切四类后端硬功；Agent 只是载体，工程深度是内核。

## 二、简历顶部摘要（对应 PDF 个人优势段）

- **Agent 编排引擎从 0 实现**：Java 21 实现 BSP（整体同步并行）执行模型 + 两级 Checkpoint 崩溃恢复；13 个 Maven 模块、170+ commits、500+ 测试 / JaCoCo 80% 门禁（INSTRUCTION/BUNDLE）/ GitHub Actions CI 全绿 + React UI 6 Tab。
- **框架可移植性实证**：Spring AI 2.0 与 LangChain4j 收敛到同一 AgentFunction 窄接口，core 模块零 LLM 框架依赖（构建级可验证）；RAG、mock、真实 LLM（DeepSeek）三类后端同一 DSL 语义一致。
- **真实环境端到端**：真实 DeepSeek key 跑通 REST→DSL→引擎→适配器→LLM→Prometheus 链路（真实 token 3000+、成本落 Grafana）；真 PG / 真 Kafka / 5 容器 Docker 部署全部实跑验证过。
- **AI 辅助开发的工程闭环**：AI 写代码、本人做架构决策与质量把关——每轮多视角 code review 修复记录 + 全部决策/坑/教训沉淀进 `docs/developer-notes/` + `docs/business/`。

## 三、项目亮点改写（七条主 bullet，按「动作→系统能力→业务价值→结果证据→个人边界」）

### 亮点 1：BSP 执行引擎 + 动态扩展（引擎核心）

> 从 0 实现 BSP 执行引擎：YAML DSL 声明 DAG 经最长路径预分层，Virtual Threads 层内并行、barrier 确定性合并，免锁并发（只读快照 + 声明序 Reducer）；在 BSP 不变量上扩出运行时条件路由（`when` 谓词 + 可达性剪枝）与有界循环（回边 + 迭代轮次），路由决策落 checkpoint 供恢复重放。

- **证据**：`agentflow-core/.../engine/BspEngine.java`；`docs/business/flows/dynamic-routing-and-loops.md`（19 条已确认规则）
- **追问钩子**：BSP vs Actor（有界 DAG 不需要消息队列管并发）；「动态路由牺牲确定性、checkpoint 重放买回来」一句话收口
- **账本**：claim-agentflow-002 ｜ 边界：单机 VT 并行（非分布式执行）

### 亮点 2：两级 Checkpoint 崩溃恢复（正确性硬功）

> 两级 Checkpoint + RecoveryProtocol：节点级完成即写防 LLM 重复计费，barrier 级快照；修复多视角评审发现的 off-by-one 层定位与 stray COMPLETED 防护两个正确性 P0（4 名独立视角确认）。

- **证据**：`engine/checkpoint/RecoveryProtocol.java`；真 PG `PostgresCheckpointManagerIT` 实跑
- **追问钩子**：宁可重复计费（软约束）也不吞未过 barrier 的脏数据（正确性硬约束）
- **账本**：claim-agentflow-003 ｜ 边界：恢复入口无生产调度器（已知边界，被问如实答）

### 亮点 3：HITL 人工审批全链路（Agent 岗 JD 逐字命中）

> HITL 审批「中断→人工决策→恢复」全链路：上下文快照随审批单落库（只含兄弟输出防恢复双跑），REST/Web UI 审批后重跑待批节点续跑；`decidedBy` 服务端推导防审计身份伪造，决策原子幂等防并发重复生效。

- **证据**：`BspEngine#approveAndResume`；`docs/business/flows/hitl-approval.md`（16 规则全确认）+ UI 第 6 Tab 审批中心
- **追问钩子**：快照只剩兄弟输出——KTD-3 防重复计费在 HITL 的镜像；审批暂停不走异常路径（paused 布尔穿透三层）
- **账本**：claim-agentflow-004 ｜ 对应 JD：「任务中断、敏感操作确认、异常恢复」（无量火/立邦措辞逐字对齐）

### 亮点 4：Kafka 解耦 + 幂等（分布式语义）

> Kafka 提交/执行解耦：消费者单条条件 UPDATE 原子认领执行权（`WHERE status='PENDING'`），at-least-once 投递下防重复消费双计费；修复 retry 被幂等防护吞掉静默失效的真 bug（本地单测全绿、Kafka 模式才暴露——「测试绿 ≠ 生产生效」）。

- **证据**：`agentflow-kafka-starter/KafkaWorkflowConsumer.java`；真 Kafka `KafkaDispatchE2eIT` 三场景实跑
- **追问钩子**：幂等做在状态机条件转移上（不是消费端注解）；`auto.offset.reset=earliest` 任务队列语义不丢订阅前消息
- **账本**：claim-agentflow-005 ｜ 边界：单 JVM 语义（send 异步无 outbox 为已知边界）

### 亮点 5：双框架适配器 + RAG 扩展点（可移植性实证）

> Spring AI 2.0 与 LangChain4j 收敛到同一 `AgentFunction` 窄接口——core 零 LLM 框架依赖（删掉任一适配器模块引擎照样编译）；RAG（检索→增强→委托）作为扩展点接入，`RagEngineZeroChangeTest` 证明引擎零改动。

- **证据**：`agentflow-adapters/langchain4j` + `demo-rag`；真实 DeepSeek E2E 门控实跑（token 3000+）
- **追问钩子**：「可移植性的证明力来自可验证性」；工具循环 ≤5 轮防死循环、usage 跨轮累加计费
- **账本**：claim-agentflow-006 ｜ 边界：RAG 为 demo 级（内存向量库/确定性 embedder 是有意的最小验证实现）

### 亮点 6：安全横切（权限 + 静态加密）

> 工具级授权（config ∪ DB、admin-only 管理、防自授特权工具）、5 处敏感列 AES-256-GCM 静态加密（`AESGCM:` 自描述前缀 + legacy 明文兼容），生产 `fromEnvStrict()` fail-closed（缺 key 拒绝明文落库）；API Key 只存 SHA-256 哈希、所有权校验防越权。

- **证据**：`api/security/` + `core/security/`；真 PG 密文 IT（列值 `AESGCM:` 前缀不含明文 + 解密还原）
- **追问钩子**：扩列判据 = 列内是否含业务敏感数据（prompt 话术/审批金额/路由上下文）；空数组非 403 的可见域语义
- **账本**：claim-agentflow-007 ｜ 对应 JD：「用户授权、敏感操作确认、风险拦截、审计日志」

### 亮点 7：质量工程与成本防线（工程素养）

> 500+ 测试 / JaCoCo 80% 覆盖率门禁 / CI 全绿；成本两道防线——提交守卫事前拦截失控 DAG（节点数/预估成本 422）+ per-workflow 预算事后告警（edge-triggered 恰一次）；踩坑主打：预算原实现只记成功路径末次、低估 ≤3x，评审抓到后改逐轮记账。

- **证据**：`.github/workflows/ci.yml`；`WorkflowSubmissionGuard` + `WorkflowBudget`
- **追问钩子**：「记账/告警非阻断，硬防护归提交守卫」分工决议；JaCoCo 驱补并发断言
- **账本**：claim-agentflow-008 ｜ 数字策略：报「500+」不写精确数（随时点增长）

## 四、HR 开场白（两版）

**短版（约 90 字）**：
> 您好，我是 27 届计算机科班应届生。个人项目 AgentFlow 是 Java 从 0 实现的 Multi-Agent 编排引擎（BSP 并发 + Checkpoint 恢复 + HITL 审批 + Kafka 解耦），500+ 测试、CI 全绿，GitHub 可点开。匹配贵司 Agent/后端方向，方便聊聊吗？

**完整版（约 140 字）**：
> 您好，我是 27 届计算机科班应届生，方向是后端工程 + Agent 工程化。核心项目 AgentFlow：Java 21 从 0 实现 BSP 执行引擎 + 两级 Checkpoint 崩溃恢复（防 LLM 重复计费），扩动态路由/循环/Kafka 解耦/HITL 审批，双框架适配器（Spring AI + LangChain4j）实证可移植性。500+ 测试 / JaCoCo 80% 门禁 / CI 全绿，真实 DeepSeek 端到端验证。GitHub 公开可查，期待进一步交流。

## 五、主张审计表（原始说法 → 建议写法 → 证据 → 边界 → 风险）

| # | 原始说法（痛点） | 建议写法（简历版） | 事实证据 | 个人边界 | 风险/待确认 |
|---|---|---|---|---|---|
| 1 | 「做了一个 Agent 编排引擎」 | 「从 0 实现 BSP 执行引擎，静态 DAG→动态路由→循环三层扩展不破坏 BSP 不变量」 | BspEngine + dynamic-routing-and-loops.md | 单机并行，非分布式引擎 | 被问「为什么不用 LangGraph4j」→ 答从 0 展示工程深度（档 C ①） |
| 2 | 「做了崩溃恢复」 | 「两级 Checkpoint 防 LLM 重复计费；修复 2 个正确性 P0」 | RecoveryProtocol + 真 PG IT | 恢复入口无生产调度器（已知） | exposed：被问『谁触发恢复』答已知边界更可信 |
| 3 | 「支持人工审批」 | 「HITL 中断→审批→恢复全链路，含 Web UI 审批中心；审计身份防伪造」 | hitl-approval.md + UI 6 Tab | 审批人=创建者∪admin | JD 逐字命中，优先放前 |
| 4 | 「接入了 Kafka」 | 「Kafka 解耦 + 原子认领幂等；修复 retry 被幂等吞掉的真 bug」 | KafkaWorkflowConsumer + E2eIT | 单 JVM 语义 | 经典「测试绿≠生产生效」故事，必被追问 |
| 5 | 「支持 Spring AI / LangChain4j」 | 「双框架收敛同一窄接口，core 零框架依赖（构建级证明）」 | adapters/langchain4j | RAG demo 级 | 「第二个适配器为什么不是第三库」→ 构建级隔离 |
| 6 | 「做了安全」 | 「工具授权 + 5 处敏感列 AES-GCM 静态加密（生产 fail-closed）」 | tool-authorization + column-encryption | 单部署单钥、无外部测评 | 「哪些列、为什么」判据要背熟 |
| 7 | 「测试很多」 | 「500+ 测试 / JaCoCo 80% 门禁 / CI 全绿 + 成本两道防线」 | ci.yml + 提交守卫/预算 | 无压测数据 | 不编造延迟/吞吐数字 |
| 8 | 「AI 辅助开发」 | （面试口径，不进简历正文）「AI 写代码，我做架构决策与质量把关」 | review 修复记录 + 真实环境验证 | 不虚构手写每行 | 诺姆四达 JD 列为加分项，主动说不回避 |
| 9 | 「有真实 LLM 接入」 | 「真实 DeepSeek 端到端（token 3000+、成本落 Grafana）；env 读 key、缺 key fail-fast」 | DeepSeekE2eIT 实跑记录 | 门控 IT（无 key 跳过） | 凭证安全叙事一并讲 |

## 六、证据补强清单与可能追问（U5/U7/U14 等单元另有深挖钩子，全索引见 `docs/developer-notes/00-interview-arsenal.md`）

- **补强 1**：把「为什么不用 LangGraph4j」练成 30s 标准答案（档 C ① 已写稿）
- **补强 2**：准备一个踩坑主打故事——预算记账低估（03-review-findings 有完整链）或 retry 被幂等吞（Kafka）
- **补强 3**：数字陈述统一「500+ 测试 / 170+ commits」（避免精确数漂移）
- **补强 4**：补最小 MCP 接入的性价比（01 号文档缺口）——比第 9 条主张更值得补
- **补强 5**：面试前跑 `git log --stat` 确保口头叙事与提交历史吻合（验真第 5 条）

> 账本维护：本文档七条主 bullet 与 claim-ledger 的 7 条 AgentFlow 主张一一对应；新增强主张时应优先走账本新增流程（先写 source_fact 再写 candidate_wording），不本文档直接扩。
