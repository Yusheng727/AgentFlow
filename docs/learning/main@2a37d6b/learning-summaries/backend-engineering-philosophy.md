# 学习总结：AgentFlow 后端工程思想全景

> 分析时间：2026-08-25 ｜ target：main@2a37d6b27e5d50687c656adbeedd2b92c0fdee92 ｜ 模式：deep-dive
> 分析范围：全仓深挖后的第一人称总结（引擎/checkpoint/适配器/HITL/Kafka/安全/RAG 七链路） ｜ 未覆盖/不可访问区域：无

## 我理解到的业务与产品

- 这是「从0复现展示后端工程深度」的项目，价值主张是工程而非生态空白 [需求已确认]（01-problem-frame 诚实声明）——所有设计决策都要能回答「为什么不用现成的」，答案是「展示物就是实现过程本身」。
- 目标用户是想在 Java 后端里编排多 Agent 的开发者 [需求已确认]（01-problem-frame 目标用户段）。
- 六个 demo 各对应一个引擎能力的最小验收 [需求已确认]（05-implementation-units）。

## 我学到的关键技术

- 我原来以为 BSP 只是个学术名词；这个项目里它是 `agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java` 的执行骨架——「同层 VT 并行 + allOf barrier + 声明序合并」三句话，换来的是免锁并发（`WorkflowContext` 全局写只在 barrier 单线程段）+ 确定性可测（同层并发写合并结果确定）；我学到**并发正确性可以靠结构（单写者）而非工具（锁）达成**。
- 我原来以为 checkpoint 就是「定期存状态」；这里它只存三类最小事实（node output/barrier 快照/已走边），可达集、onError 集、下一轮起点恢复期全重算（`BspEngine#computeReachable` BFS）；我学到**事实与派生态分离在无事务环境下的容错价值**——存得越少，崩溃窗口内状态不一致的机会越小。
- 我原来以为「支持多框架」是加抽象层；这里它是构建级隔离（core pom 零 LLM 框架依赖）+ 两方法窄接口（`AgentFunction`）+ 框架知识外移（`ErrorClassifier.composed()` 注入）；我学到**可移植性的证明力来自可验证性**——「删掉这个模块引擎照样编译」比任何架构图有说服力。
- 我原来以为重试就是 retry 循环；这里是三层预算组合（RetryPolicy 3 attempt × schema-retry ≤2 = 9 上限）+ 分类前置（transient 才重试）+ 记账随行（LC4j 每轮 metricAndBudget——取消/失败轮也计费进预算）；我学到**重试的难点不是循环而是「何时停、算多少钱」**。
- 我原来以为幂等是消费端注解；这里是业务状态机的条件转移（`tryClaim` 单条 UPDATE PENDING→RUNNING）；我学到**幂等做在状态机上，对 Kafka/本地/未来 gRPC 三种派发统一生效**，且 retry 端点必须知情（复位 PENDING 否则被幂等正确吞掉）。

## 我理解到的设计思想

- **「确定性买回」是这个项目的第一设计哲学**，至少四例：barrier 确定性合并（买回并发确定性）、路由决策重放（买回执行路径确定性）、round 维度重放（买回迭代确定性）、审批快照（买回暂停/恢复确定性）。共同模式：把不可重来的副作用转成可重放的事实。
- **渐进增强的可空注入**（BspEngine 构造器链 3→6 参，null=能力关闭）：95 个 callers 里旧调用方零破坏地获得了新横切能力——扩展不兼容的代价可以靠「可空+委托链」压到零。
- **宽严分离的装配**（`ColumnEncryptors.fromEnv/fromEnvStrict`）：同一实现两种装配姿势，dev 体验与生产安全不互搏。
- **演示诚实性纪律**：UI 写操作无 mock fallback、5xx 不降级、空数组非 403、fail-closed——每条对应一个具体的「假成功/假安全」事故场景，全部有 review 溯源。

## 我发现的工程实践

- **测试锁语义而非实现**：approveAndResume 的 9 个测试方法每个锁一个语义（resume/reject/再暂停/兄弟输出保留/takenEdges 预置）；RagEngineZeroChangeTest 锁「引擎零改动」这个架构性质本身。
- **SQL 与 RowMapper 提为 package-private static 单一真相源**（`PostgresCheckpointManager.TRY_CLAIM_SQL` 等）——H2 测试跑同一 SQL 防复制漂移。
- **注释记录「为什么不」**：Spring 适配器 cancel 注释写明放弃 inFlight 表的 race 论证；LC4j 不用 AiServices 的理由写在 chatWithTools javadoc——决策及其反方都被留存。
- **review 发现进文档**：`docs/developer-notes/03-review-findings.md` 强制同步（CLAUDE.md 约定），「测试绿 ≠ 生产生效」类教训全部沉淀。
- **env 名坑记录**：AGENTFLOW_API_API_KEYS（不是 AGENTFLOW_API_KEYS）这类宽松绑定陷阱写进 CLAUDE.md 防重蹈。

## 我曾经误解、后来修正的点

- 以为「失败层不写 barrier」是遗漏——其实是 KTD-3 显式语义（barrier 记录"已完成"层，失败层未完成，Recovery 会在该层找 COMPLETED 节点级记录）。
- 以为审批暂停该走异常路径——实际是 paused 布尔穿透三层（runStep 返回值→runRounds 短路→execute 不记 FAILED），异常控制流会被 catch/finally 误伤暂停语义。
- 以为 kafka JsonSerializer 废弃是小事——它牵出 wire 格式自持（String+自有 mapper+JavaTimeModule 禁时间戳数组）的一整套纪律。
- 以为 `POST /retry` 直接重跑——Kafka 模式必须先复位 PENDING，否则消费端幂等会把重试消息正确地吞掉（静默失效）。

## 仍待确认的问题

见 open-questions.md Q1–Q14（Redis 定位/参数依据/恢复入口并发防护/审批超时/key 轮换等）。

## 后续建议深挖的方向

| 方向 | 理由 |
|---|---|
| observability 链路（AgentFlowMetrics/trace/Grafana 面板映射） | 面试常问「你的系统怎么观测」；指标族设计与 P50/P95/P99 直方图取舍有讲头（核心程度中高/跨模块） |
| demo-api 装配全景（ApiConfig 21 beans 的生产形态） | 「怎么把库变成服务」的完整参考；mock/real/kafka 三开关的条件装配矩阵（业务价值高） |
| UI 六 Tab 数据流（KTD-1 mock fallback 全貌） | 前后端契约+降级纪律；面试可讲「演示不白屏 vs 写不假成功」的取舍（风险低但叙事完整） |
