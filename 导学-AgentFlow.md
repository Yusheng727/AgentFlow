# 导学-AgentFlow

> 项目导学（2026-08-31 生成）：学习路径、源码阅读顺序、核心原理、设计决策与验证建议。
> 配套：`面经-AgentFlow.md`（简历 bullet + 20 个主问题 + 口播 + 追问链 + 源码证据索引）。

## 1. 前置知识（面试高频标注）

| 知识点 | 为何需要 | 在本项目中的位置 | 高频度 |
| --- | --- | --- | --- |
| BSP（Bulk Synchronous Parallel）模型 | 整个执行引擎的心智模型：层内并行 + barrier 同步 + 确定性合并 | 执行引擎核心（见推荐阅读 1） | ★★★ |
| Java Virtual Threads | 层内并行与限流的实现底座，必被追问 | 引擎并发执行 + 数据库写入限流 | ★★★ |
| CompletableFuture.allOf | barrier 语义的实现手段（最快也要等最慢） | 引擎屏障同步 | ★★★ |
| DAG / 最长路径分层 | YAML 声明图如何变成可调度步骤序列 | DSL 分层器（level[v]=max(level[u])+1） | ★★★ |
| Checkpoint / 崩溃恢复思想 | 两级检查点与恢复协议，项目最有深度的单元 | 检查点存储 + 恢复协议 | ★★★ |
| 幂等性（条件状态转移） | Kafka at-least-once 下防重复计费的关键 | 异步消费端原子认领 | ★★★ |
| Kafka 投递语义 | at-least-once / auto.offset.reset 的任务队列语义 | 异步分发模块 | ★★ |
| AES-256-GCM | 敏感列静态加密的算法选择依据 | 列加密器 | ★★ |
| SpEL 与安全求值 | prompt 模板解析，禁 T() 防注入 | 表达式求值器 | ★★ |
| Micrometer / Prometheus | 可观测三层中的指标层 | 指标注册 + Grafana 面板 | ★★ |
| 数据库 upsert 幂等（ON CONFLICT） | 检查点写入幂等 + 测试方言坑（H2 不支持） | PG 存储层 + V1–V8 迁移 | ★★ |

## 2. 重点亮点与学习顺序（先看这个）

| 亮点标题 | 为什么重要 | 通用技术关键词 | 先看哪些文件 | 建议学习顺序 |
| --- | --- | --- | --- | --- |
| ① 并发编排（BSP 执行模型） | 项目技术内核，所有追问的根基 | BSP、Virtual Threads、只读快照、确定性合并 | 引擎执行入口 + 上下文/归并器（见推荐阅读 1、2） | 1 |
| ② 崩溃恢复（两级检查点） | 面试官判断「真做没做过」的首选深挖区 | 两级检查点、恢复协议、幂等写入 | 恢复协议 + 检查点管理器（推荐阅读 3、4） | 2 |
| ③ 人机协同中断恢复（HITL） | Agent 岗 JD 逐字命中：中断→审批→恢复 | 暂停语义、上下文快照、审计身份 | 引擎审批入口 + 审批控制器（推荐阅读 5） | 3 |
| ④ 异步解耦与幂等（Kafka） | 分布式语义 + 「测试绿≠生产生效」真 bug 故事 | at-least-once、原子认领、条件状态转移 | 异步分发模块（推荐阅读 6） | 4 |
| ⑤ 可移植性（双框架适配器） | 差异化定位：构建级证明框架无关 | 窄接口、扩展点、依赖隔离 | 两个适配器实现（推荐阅读 7、8） | 5 |
| ⑥ 安全与成本治理 | 横切能力面：授权/加密/守卫/预算/观测 | 最小授权、fail-closed、两层成本防线 | 安全包 + 提交守卫 + 指标（推荐阅读 9–11） | 6 |

## 3. 必备知识点 checklist

- [ ] 能画出 BSP 一轮执行的时序：提交层内并行 → barrier → channel 归并 → 下一层
- [ ] 能说出两级检查点各自防什么（节点级防 LLM 重复计费 / barrier 级定义恢复边界）
- [ ] 能描述崩溃恢复的定位规则：从「最近完成的屏障的下一步」接续，不是「下一步的下一步」
- [ ] 能解释为什么只读快照 + 声明序归并能同时做到「免锁」和「确定性」
- [ ] 能说出瞬态错误 vs 致命错误的分类标准与重试边界（指数退避、最大次数、组合上限）
- [ ] 能讲清 Kafka 消费端为什么用条件状态转移做幂等，而不是依赖去重注解
- [ ] 能说出「记账/告警」与「硬性拦截」的分工（运行时告警 vs 提交前 422 拒绝）
- [ ] 能说出加密的边界判据（列内是否含业务敏感数据），不是「越多越好」
- [ ] 能讲 2 个「多视角评审抓到、测试全绿但生产不生效」的具体案例
- [ ] 能诚实列出已知边界（单机并行、无外部调度器触发恢复、单 JVM 消息语义、demo 级 RAG）

## 4. 推荐阅读（结合仓库）

| 主题 | 通用技术点 | 建议阅读位置 | 预计时间 | 读完能回答什么 |
| --- | --- | --- | --- | --- |
| 1. 引擎执行主循环 | BSP、barrier、轮次迭代 | `agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java` | 45 min | 一轮执行发生了什么；动态路由/循环/审批如何共用同一骨架 |
| 2. 上下文与归并 | 只读快照、声明序合并 | `agentflow-core/src/main/java/com/agentflow/engine/WorkflowContext.java`、`ChannelReducer.java` | 20 min | 免锁怎么实现；并发写同一通道为什么不靠运气 |
| 3. 恢复协议 | 崩溃定位、输出重放、迟到写入防护 | `agentflow-core/src/main/java/com/agentflow/engine/checkpoint/RecoveryProtocol.java` | 30 min | 崩溃后从哪续跑；两个正确性缺陷是怎么修的 |
| 4. 检查点存储 | 幂等 upsert、连接限流、列加密 | `agentflow-core/src/main/java/com/agentflow/engine/checkpoint/PostgresCheckpointManager.java` | 30 min | 写入怎么保证幂等；为什么同步写 + 限流 |
| 5. 审批暂停与恢复 | 中断→审批→续跑 | `agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java`（审批入口）+ `agentflow-api/src/main/java/com/agentflow/api/ApprovalController.java` | 30 min | 暂停语义怎么穿透执行循环；恢复时怎么防双跑 |
| 6. Kafka 分发与消费 | at-least-once、原子认领 | `agentflow-kafka-starter/src/main/java/com/agentflow/kafka/KafkaWorkflowConsumer.java`、`KafkaWorkflowDispatcher.java` | 25 min | 重复投递为什么不会双跑；消费位移语义 |
| 7. Spring 适配器 | 模板解析、token 计账 | `agentflow-adapters/spring-ai/src/main/java/com/agentflow/adapters/springai/SpringAiAgentAdapter.java` | 20 min | 框架调用怎么收敛；usage 怎么回传 |
| 8. LangChain4j 适配器 | 裸模型工具循环 | `agentflow-adapters/langchain4j/src/main/java/com/agentflow/adapters/langchain4j/LangChain4jAgentAdapter.java` | 25 min | 没有内置工具闭环时自己怎么写；轮次上限与跨轮计账 |
| 9. API 安全 | 鉴权、所有权、工具授权、守卫 | `agentflow-api/src/main/java/com/agentflow/api/security/`（`ApiKeyAuthFilter.java`、`WorkflowSubmissionGuard.java`、`CallerToolAllowlist.java`） | 25 min | API Key 为什么只存哈希；提交守卫为什么在持久化前 |
| 10. 列加密 | AES-GCM、fail-closed | `agentflow-core/src/main/java/com/agentflow/security/`（`AesGcmColumnEncryptor.java`、`ColumnEncryptors.java`） | 20 min | 密文自描述前缀与旧数据兼容；生产缺密钥为什么拒绝启动 |
| 11. 指标与成本 | 指标挂钩、定价表、预算告警 | `agentflow-core/src/main/java/com/agentflow/observability/AgentFlowMetrics.java`、`CostCalculator.java`、`WorkflowBudget.java` | 20 min | 指标为什么必须引擎挂钩而不是只在测试里记 |
| 12. DSL 解析与分层 | 三层校验、最长路径 | `agentflow-core/src/main/java/com/agentflow/dsl/WorkflowDSLParser.java`、`DAGLayerer.java`、`SemanticValidator.java` | 25 min | 环怎么检测；非法 YAML 怎么 fail-fast |
| 13. 数据库迁移 | schema 演进 | `agentflow-core/src/main/resources/db/migration/V1__checkpoint_schema.sql` 至 `V8__r22_encrypt_routing_and_definitions.sql` | 15 min | 表结构与列型演进的完整脉络（含 JSONB→TEXT 加密改造） |
| 14. 前端门面 | 看板、提交、轨迹、审批中心 | `agentflow-ui/src/components/Dashboard.tsx`、`PipelineView.tsx`、`ApprovalCenter.tsx` | 20 min | 执行拓扑怎么可视化；读降级与写不降级的纪律 |

## 5. 自学提醒

- 若某文件或原理看不懂，请继续追问 AI；本技能负责给学习路径与题目，不提供逐行讲解。
- 文档同步看：`docs/plans/agentflow/03-key-technical-decisions.md`（设计决策）、`docs/business/flows/`（11 条业务流程规则）、`docs/developer-notes/02-bugs-and-fixes.md` 与 `03-review-findings.md`（踩坑与评审记录）——面试讲「修过什么」比讲「写过什么」更有说服力。

## 6. 项目技术定位

**后端（Java）为主 + AI 工程化交叉**。依据：核心是并发执行模型、崩溃一致性、分布式解耦、安全横切四类后端硬功；LLM 只是调度对象，引擎本身零大模型框架依赖（构建级可验证）。投递时可按 JD 在「Java 后端」与「AI 应用/Agent」间切换 bullet 排序。

## 7. 核心原理解析

1. **BSP 如何同时做到并发与确定**：问题——多节点并行写共享上下文，锁会引入死锁与顺序不确定性。机制——同层节点拿只读快照互不可见，输出在屏障后按声明顺序归并，写冲突由归并策略裁决。落点——引擎每轮的快照-执行-屏障-归并循环。
2. **两级检查点如何换崩溃一致性**：问题——长时间任务崩溃后，要么整段重跑（重复付费），要么记录太细（写放大）。机制——节点完成即持久化输出（部分提交），屏障作为逻辑提交点；恢复时重放已完成节点的输出、重跑崩溃层。落点——检查点存储的幂等 upsert + 恢复协议的重放逻辑。
3. **审批暂停为什么不用异常实现**：问题——用异常表达「需要人工」会把控制流和错误流混在一起，中断点难精确。机制——识别为独立的结果类型，沿暂停标志逐层退出，把兄弟节点输出做成上下文快照随审批单落库。落点——屏障处的暂停识别 + 审批恢复入口。
4. **at-least-once 下的幂等从哪来**：问题——消息可能重复投递，重复执行等于重复计费。机制——消费端用条件状态转移（仅当任务仍处于待执行态才认领成功）替代「先查后改」，数据库保证只有一个消费者胜出。落点——异步消费模块的原子认领 SQL。
5. **可移植性为什么靠窄接口而不是抽象工厂**：问题——框架能力差异大，宽抽象会把差异藏进核心。机制——核心只依赖统一的执行单元接口，两个框架各自在适配器内消化全部框架调用；新模块的依赖清单里不出现另一个框架即为证明。落点——适配器目录下两个互不依赖的实现 + 构建配置。
6. **观测为什么必须「引擎挂钩」**：问题——指标只在测试里记，生产永远没有样本，面板全空。机制——指标作为引擎构造的可选依赖，执行成功/失败、节点耗时、token、成本都在引擎内记录；再配 Prometheus 抓取端到端验证。落点——引擎与指标注册的接线 + 起服后的抓取端点。

## 8. 关键设计决策

| 决策点 | 备选 | 取舍 | 风险 | 验证 |
| --- | --- | --- | --- | --- |
| BSP vs Actor 模型 | Actor 消息驱动 | 有界 DAG 不需要消息队列管并发；BSP 的显式屏障让恢复边界清晰 | 灵活性低于纯事件驱动（对工作流场景是优点） | 单测 + 多拓扑 demo（串行/双层 fork-join） |
| 两级检查点 vs 单级 | 仅节点级 / 仅屏障级 | 节点级防重复计费，屏障级定恢复边界 | 写放大（用信号量限流对齐连接池） | 真 PostgreSQL 集成测试实跑 |
| 免锁快照 vs 加锁 | ReadWriteLock | 快照消除锁竞争，声明序归并保证确定性 | 快照内存开销（规模可控） | 并发归并测试 |
| 运行时条件路由：剪枝 vs 放弃预分层 | 动态 ready-set | 保留 BSP 预分层，只跑「可达」节点，被切断的下游标记跳过 | 路由决策丢失则恢复不确定 → 决策落库重放 | 条件路由/循环 demo + 恢复不复活跳过节点的测试 |
| 预算：记账告警 vs 运行中阻断 | 超限即中止 | 语义拍板为告警（edge-triggered 恰一次）；硬防护交给提交守卫事前拦截 | 只告警不拦截 → 守卫兜底（422） | 预算集成测试 + 守卫单元测试 |
| 加密：列级 vs 库级/TDE | 库级加密 | 判据=列内业务敏感数据；密文自描述前缀 + 旧明文行兼容 | 半吊子加密（部分列明文）→ 生产双严格装配 fail-closed | 真库密文集成测试（列值不含明文 + 解密还原） |
| 消息：Kafka vs 本地线程 | 本地虚拟线程异步 | 提交/执行解耦做演示口径；属性开关按需启用，默认不变 | 单 JVM 语义、无发送前落库（如实告知边界） | 真 Kafka 端到端 + 重放幂等测试 |

## 9. 量化与验证（含待测，建议）

已验证（有真实记录）：
- 全仓测试 500+，覆盖率门禁 80%（指令级/模块级），CI 全绿；约 170+ 提交、13 个 Maven 模块。
- 真实大模型端到端：真实 key 跑通 REST→DSL→引擎→适配器→LLM→指标全链路，单次执行真实 token 3275、估算成本约 7.7e-4 USD、2 节点 20.5 秒，数据落 Prometheus/Grafana。
- 真环境验证：真 PostgreSQL 集成测试（含密文断言）、真 Kafka 端到端（含重放幂等）、5 容器 Docker 部署 + Grafana 面板数据齐全。

建议补测（当前标「待测」）：
- 压测数据（吞吐/延迟分位）：待测——建议对固定拓扑跑基准，报告 P50/P95 与资源占用，避免只讲架构不讲数字。
- 恢复成功率统计口径：待测——建议在崩溃注入测试中统计「恢复后不重跑已完成节点」的比率。
- 消息积压下的消费吞吐：待测——建议用 Kafka 端到端脚本叠加投递速率做衰减曲线。
