# 产品意图假设

> 分析时间：2026-08-25 23:55 ｜ target：main@2a37d6b27e5d50687c656adbeedd2b92c0fdee92 ｜ 模式：snapshot
> 取证方式：CodeGraph 路由/调用者证据 + 源码注释 + git 交付记录 + 计划文档
> 分析范围：从代码行为/错误文案/接口设计提炼的可观察行为与动机推测
> 未覆盖/不可访问区域：无正式 PRD 迭代记录（本项目为个人简历项目，需求文档即 `docs/plans/agentflow/02-requirements.md`）

| 功能/规则 | 推测的产品目标 | 证据 | 可信度 | 待确认点 |
|---|---|---|---|---|
| 提交接口 422 拦截超 500 节点/超预估成本的工作流 | 防失控/恶意提交起无界 Virtual Thread 与烧 LLM 成本 | `agentflow-api/src/main/java/com/agentflow/api/security/WorkflowSubmissionGuard.java`（javadoc 明言「预防性拦截」+ DEFAULT_MAX_NODES=500） | [代码已确认] 行为；动机见 javadoc 自述 [历史已确认] | 500/成本阈值的数值依据（压测或经验值）未在可访问资料中发现 [待确认] |
| 审批聚合端点对非 admin 返回空数组而非 403 | 不泄漏他人审批的存在性（空数组语义上不可区分「没有」与「不让看」） | `agentflow-api/src/main/java/com/agentflow/api/ApprovalCenterController.java`（listByCreatedBy(caller) 遍历）+ CLAUDE.md「空数组非 403，不泄漏存在性」 | [代码已确认] 行为；可见域设计动机 [合理推断]（支撑：代码注释+CLAUDE.md 交付记录一致） | — |
| 审批 decidedBy 由服务端从 caller 推导而非客户端传入 | 防伪造审批人身份 | `agentflow-api/src/main/java/com/agentflow/api/ApprovalController.java`（CLAUDE.md「decidedBy 服务端推导防伪造」） | [代码已确认] 行为；安全动机 [历史已确认]（U6 交付记录） | — |
| UI 写操作（审批决策/重试）不做 mock fallback，读操作降级 mock | 防止后端离线时用户误以为审批成功；同时保证浏览演示不白屏 | `agentflow-ui/src/lib/api.ts`（decideApproval 无 withMockFallback 包裹；读端点全部包裹） | [代码已确认] 行为；动机 javadoc 自述 [历史已确认] | — |
| 生产装配缺 AGENTFLOW_ENCRYPTION_KEY 直接抛异常拒绝启动（fail-closed），dev 缺 key 降级明文+warn | 生产敏感列强制加密优先于可用性；开发体验优先于安全（可接受的明文） | `agentflow-core/src/main/java/com/agentflow/security/ColumnEncryptors.java`（fromEnv/fromEnvStrict 双工厂注释）+ starter 生产装配 | [代码已确认] 行为；宽严分明的动机 [合理推断]（支撑：两工厂注释一致表述「拒绝明文落库」vs「开发不被加密阻塞」） | — |
| 引擎迭代硬上界 MAX_TOTAL_ROUNDS=1000 + per-loop max_iterations 双保险 | 防 YAML 编写错误（回边缺退出条件）导致引擎死循环烧 LLM 成本 | `agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java#L78`（注释「纵深防御」） | [代码已确认] 行为；动机 [合理推断]（支撑：字段注释自述纵深防御意图） | 1000 的量级选择依据 [待确认] |
| Kafka 消费者丢弃「从未 initWorkflow 的任意 workflowId」消息 | 防止绕过提交接口直接往 topic 注入消息伪造执行记录/放大成本 | `agentflow-kafka-starter/src/main/java/com/agentflow/kafka/KafkaWorkflowConsumer.java`（「防 ledger 污染 + 越 trust boundary 的成本放大」） | [代码已确认] 行为；安全动机 [历史已确认]（review 修复记录） | — |
| 工具授权 admin-only（grant/revoke），普通 caller 只能自读 | 防止持普通 key 者自授敏感工具（财务 DB 查询等）权限 | `agentflow-api/src/main/java/com/agentflow/api/security/ToolGrantController.java`（POST/DELETE admin-only，GET 自读/admin 查任意） | [代码已确认] 行为；CLAUDE.md「防自授特权工具」[历史已确认] | — |
| retry 端点在 Kafka 模式先复位 PENDING 再派发 | 否则消费端幂等终态跳过会吞掉 retry 消息、静默失效 | `agentflow-api/src/main/java/com/agentflow/api/WorkflowController.java#L322`（注释「消费者会跳过 retry 派发的消息，retry 静默失效。先复位 PENDING」） | [代码已确认] 行为；动机注释自述 [历史已确认]（ce-code-review 3 reviewer conf 100） | — |
| 定义重取 3 次重试 ×1s（DEF_LOOKUP_RETRY） | 为跨节点部署留 read-after-write 容忍窗口（Kafka 模式下提交与执行可能不同节点） | `agentflow-api/src/main/java/com/agentflow/api/WorkflowExecutionService.java#L25`（javadoc「为未来跨节点留容忍窗口」） | [代码已确认] 行为；动机 [合理推断]（支撑：javadoc 自述 + Kafka 模式存在性） | 当前单 JVM 部署下该重试是否实际触发过 [待确认] |
| 六个 demo 模块各自独立可跑（串行/fork-join/条件/循环/RAG） | 每个引擎能力配一个最小可验收端到端示例，演示即测试 | `demo-supplier-risk/src/main/java` 等 6 模块 + `docs/plans/agentflow/05-implementation-units.md` 验收列 | [需求已确认]（05 计划每个 Unit 带 demo 验收） | — |
| 项目定位「从0复现展示工程深度」而非「填补 Java 生态空白」 | 建立面试叙事的诚实性——避免被质疑重复造轮子 | `docs/plans/agentflow/01-problem-frame.md`「诚实声明」段 | [需求已确认] | — |
