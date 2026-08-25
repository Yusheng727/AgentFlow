# 学习总结：面试问题覆盖地图（按追问链组织）

> 分析时间：2026-08-25 ｜ target：main@2a37d6b27e5d50687c656adbeedd2b92c0fdee92 ｜ 模式：deep-dive
> 分析范围：把深挖产出重组为「面试官追问链 → 弹药位置」的速查地图 ｜ 未覆盖/不可访问区域：无

## 我理解到的业务与产品

- 项目卖点是工程深度而非功能完备 [需求已确认]——面试回答的基调应是「我做了什么取舍、为什么」，不是「我功能多全」。

## 我学到的关键技术（按追问链组织）

**链 1：执行模型**
- Q「为什么选 BSP 不用 Actor/事件驱动？」→ `docs/learning/main@2a37d6b/technical-decisions/bsp-over-actor.md`（不变量守住论 + 免锁/可测/恢复简单三收益 + barrier 瓶颈坦白）
- Q「动态路由下 BSP 怎么保留？」→ 可达性剪枝 + SKIPPED + checkpoint 存已走边恢复期 BFS 重算（`docs/learning/main@2a37d6b/implementation-walkthroughs/bsp-engine-execution-loop.md` §3 resolveTakenEdges/computeReachable）
- Q「循环/回边呢？」→ 回边豁免分层 + runRounds 轮次循环 + 双 active 集合 + 双保险上限（同上 §3 runRounds 段）

**链 2：容错与恢复**
- Q「崩溃了怎么办？」→ 五步恢复算法 + off-by-one 修复 + stray 防护（`docs/learning/main@2a37d6b/implementation-walkthroughs/checkpoint-recovery-protocol.md` §3）
- Q「为什么不重复计费？」→ 节点级 checkpoint COMPLETED 当下同步写 + 恢复跳过+重放输出（同上 §5 窗口 A）
- Q「超时 abort 后在飞节点写出 stray 记录呢？」→ FAILED 态整层重跑，宁可重复计费不读孤立输出（同上 stray 段——R3 软约束 vs 正确性硬约束）
- Q「重试策略？」→ 3 attempt × schema-retry 2 = 9 上限 + transient 分类 + 退避纳秒精度（`bsp-engine-execution-loop.md` §4 RetryPolicy）

**链 3：HITL**
- Q「人工审批怎么实现？」→ 暂停买回三态机 + 四层对齐（引擎 paused 标志/服务层不覆盖/幂等决策/UI 第四列）（`docs/learning/main@2a37d6b/implementation-walkthroughs/hitl-approval-lifecycle.md`）
- Q「审批期间状态怎么恢复？」→ contextSnapshot 含兄弟输出 + approveAndResume firstExcluded 防双跑 + takenEdges 预置（同上 + checkpoint walkthrough §3）
- Q「两个人同时审批？」→ confirmApproval 条件 UPDATE 恰一胜出（同上 §8）

**链 4：Agent 接入**
- Q「怎么支持多框架？」→ 构建级隔离 + 6 实现互换 + RAG 三级实证火箭（`docs/learning/main@2a37d6b/technical-decisions/zero-framework-core.md` + `docs/learning/main@2a37d6b/implementation-walkthroughs/rag-extension-point.md`）
- Q「框架升级呢？」→ 适配器窄表面一个类，mutable deque/吞异常等坑全隔离在单文件（`docs/learning/main@2a37d6b/implementation-walkthroughs/dual-adapter-comparison.md`）
- Q「工具调用的安全？」→ SafeToolExecutor 三重防御 + 反编译取证方法论（同上 §4/§11）

**链 5：分布式**
- Q「为什么加 Kafka？」→ 提交/执行解耦 + 为跨节点铺路（`docs/learning/main@2a37d6b/implementation-walkthroughs/kafka-dispatch-decoupling.md`）
- Q「重复消费？」→ at-least-once + tryClaim 状态机幂等 + unstaged 丢弃三防（同上 §3）
- Q「retry 在 Kafka 模式的坑？」→ 必须复位 PENDING 否则被幂等吞掉（同上 §11——3 reviewer 确认的 P1）

**链 6：安全**
- Q「安全怎么做的？」→ 五层纵深（认证/所有权/工具授权/守卫/加密）每层对应攻击场景（`docs/learning/main@2a37d6b/implementation-walkthroughs/api-security-layers.md` §11）
- Q「加密为什么 fail-closed？」→ 宽严双工厂：dev 体验与生产安全不互搏（同上）
- Q「拖库了会泄密吗？」→ 5 列 AESGCM 密文 + key 只在 env + hash 驻留（同上 §5）

**链 7：诚实性/开放问题（高频压力测试）**
- Q「为什么不用 LangGraph4j？」→ 01-problem-frame 诚实声明（简历项目定位，价值在过程）
- Q「单 JVM 的 Kafka 有意义吗？」→ 坦白 + read-after-write 容忍窗口已埋（3×1s）+ 真 E2E 已验证（kafka walkthrough §8）
- Q「哪些没做？」→ open-questions Q1-Q14（Redis 未消费/恢复并发防护/key 无轮换/审批无超时）——**主动知道边界比假装完备可信得多**。

## 我理解到的设计思想

面试的元叙事：所有深挖收敛为一个词——**「确定性买回」**（barrier 合并/路由重放/轮次重放/审批快照四例同构）。这是比任何单点技术更高级的展示物：证明你有跨场景抽象设计模式的能力。

## 我发现的工程实践

- 每个修复有 review 溯源、每个「不做」有注释论证、每个参数有出处记录——面试被追问细节时能下钻到 commit/行号级（本地图各条目均带文件锚点）。

## 我曾经误解、后来修正的点

- 见 `docs/learning/main@2a37d6b/learning-summaries/backend-engineering-philosophy.md`「误解修正」四条（失败层不写 barrier/审批非异常路径/wire 自持/retry 复位）——这些「我一开始理解错」的点正是面试中展示学习能力的素材。

## 仍待确认的问题

Q1–Q14（open-questions.md）——面试前至少把 Q3（守卫参数依据）/Q7（恢复触发路径）/Q8（恢复并发防护）想好「这是已知边界+我的改进思路」版本的回答。

## 后续建议深挖的方向

三个 walkthrough 级候选（见 philosophy 总结尾部）：observability 链路 / demo-api 装配全景 / UI 数据流——覆盖「怎么观测/怎么部署/怎么用」剩余三类面试问题。
