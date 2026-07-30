# 面试弹药总览

> 面试前一晚扫一遍。每个单元列出「能讲的故事 + 面试官可能深挖的点 + 你可以反问的点」。
> 故事要从 [02-bugs-and-fixes](./02-bugs-and-fixes.md) / [03-review-findings](./03-review-findings.md) / [01-implementation-rationale](./01-implementation-rationale.md) 里提取，这里只放索引和「钩子」。

---

## 自我介绍 30 秒版

「AgentFlow 是我从零复现的 Java 原生 Multi-Agent 编排引擎，YAML 声明工作流，BSP 执行模型驱动多 Agent 协作，支持崩溃恢复。技术栈 Java 21 Virtual Threads + Spring Boot 4.1 + Spring AI 2.0 + PostgreSQL。我做了 15 个实现单元，从 DSL 解析、BSP 引擎、Agent 适配器、容错机制到两级 Checkpoint 持久化。每个单元都过 `mvn verify` + JaCoCo 80% 门禁 + 多 agent code review。」

**深挖钩子**（等面试官问，再展开）：BSP 是什么 / 为什么从零复现 / Virtual Threads 怎么用 / Checkpoint 怎么恢复 / code review 怎么做。

---

## U1 — YAML DSL 解析

**可讲故事**：
- 三层校验（Jackson 类型 → 语义层 → DAG 完整性），fail-fast 给精确错误信息
- 最长路径分层算法（`level[v]=max(level[u])+1`）把 DAG 转成 super-step 序列
- JSON Schema 供 IDE 校验，开发者写 YAML 有提示

**深挖点**：
- 怎么检测环路？（拓扑排序 / DFS 三色标记）
- channel 的 Reducer 策略怎么声明？（YAML `channels` 段 + enum）
- 为什么 Jackson 配 SNAKE_CASE + ACCEPT_CASE_INSENSITIVE_ENUM？（YAML 惯例 snake_case，enum 大小写容错）

**反问准备**：暂无，U1 较基础。

---

## U2 — BSP 执行引擎（核心难点）

**可讲故事**：
- Virtual Threads 并行 + `CompletableFuture.allOf` barrier 同步——最快也要等最慢的
- 只读快照（`Map.copyOf`）保证同 super-step 节点互不可见
- Reducer 确定性合并（声明序），并发写同 channel 不靠运气
- 异常隔离：单节点抛异常不影响兄弟节点，barrier 后聚合抛
- 经 8 人 ce-code-review + 11 修复（null output / inputs 透传 / catch-all / cancel 守卫 / CONCAT 扁平 / MAX 精度 / 失败层不写 barrier）

**深挖点**：
- 为什么用 VT 不用线程池？（见 [01 rationale](./01-implementation-rationale.md#为什么用-virtual-threads--semaphore20-限流)）
- barrier 等最慢节点，慢节点怎么处理？（节点级超时 + cancel(true) 中断 VT）
- 只读快照怎么实现的？（WorkflowContext.readOnlySnapshot() 返回不可变视图，put 抛 UnsupportedOperationException）
- Reducer 的 MAX 精度问题是什么？（早期版本 Number 比较用 double 损失精度，改用 BigDecimal/类型感知比较）

**反问准备**：面试官如果问「这个引擎和 LangGraph 比有什么优势」——答：BSP 的确定性和显式 super-step 让恢复边界清晰，LangGraph 的图遍历恢复语义更模糊。但诚实声明：AgentFlow 是从零复现展示工程深度，不填补生态空白。

---

## U3 — Spring AI 适配器层

**可讲故事**：
- Spring Boot 3.4 → 4.1.0 bump（Spring AI 2.0 需 Spring Framework 7 + Jackson 3），踩了版本对齐的坑
- SpEL 解析 prompt 模板，禁 `T()` 防注入（见 [01 rationale](./01-implementation-rationale.md#为什么-spel-解析-prompt-模板禁用-t)）
- Spring AI 2.0 mutable deque 坑：`.chatResponse()` 与 `.content()` 各触发一次 advisor 链，第二次撞空 deque → 只调一次 `.chatResponse()`
- cancel() 降级：Spring AI 2.0 ChatClient 同步阻塞无 HTTP abort 钩子，cancel() best-effort no-op，真正中止靠 NodeExecutor 的 `future.cancel(true)` 中断 VT

**深挖点**：
- Spring AI 2.0 和 1.0 的区别？（Transient/NonTransientAiException 标记类移除，按 cause 粗分类）
- Advisor Chain 怎么用？（TokenCountingAdvisor 接 Micrometer，LoggingAdvisor 写 ExecutionTrace）
- cancel 不能真中止 LLM 调用，那超时后还在跑的 LLM 怎么办？（best-effort，token 计费可能继续；KTD-6 v4.3 决议，记为 known limitation）

**反问准备**：面试官问「为什么不用 LangChain4j」——答：plan 决定 v1 只做 Spring AI 适配器，LangChain4j 推迟 v1.1。所有 Spring AI 调用收敛在适配器窄表面，2.0→2.1 迁移只碰适配器（KTD-7 可移植性）。

---

## U4 — 容错机制

**可讲故事**：
- 三层链路：Timeout → ErrorClassifier（transient vs fatal）→ Retry（指数退避 1s→2s→4s，max 3）→ ErrorHandler（abort 前 context 补偿）
- retry 预算组合式：3 attempt × 内含 schema-retry ≤2 = 9 上限，封顶失控
- 失败传播：非终态 super-step 节点重试耗尽/fatal → 工作流 FAILED abort，不推进下游

**深挖点**：
- transient vs fatal 怎么分？（IOException/Timeout/网络 → transient；SpEL + 400 参数错误 → fatal）
- 指数退避为什么 1s→2s→4s？（base × 2^n，max 3 次，总等待 7s，避免重试风暴）
- ErrorHandler 为什么 v1 只能改 context 不能跳转路径？（跳转是 v2 动态路由能力，v1 静态 DAG）

**反问准备**：暂无。

---

## U5 — 两级 Checkpoint + Recovery（★面试重点★）

**可讲故事**（这是最有深度的单元，优先讲）：
- 两级 checkpoint 设计（见 [01 rationale](./01-implementation-rationale.md#为什么用两级-checkpoint节点级--barrier-级)）
- off-by-one 修复（见 [01 rationale](./01-implementation-rationale.md#为什么-off-by-one-修复查-nextsuperstep-而非-nextsuperstep-1)）
- **两个 P0 修复**（见 [02-bugs-and-fixes Bug-4/5](./02-bugs-and-fixes.md#bug-4p0-崩溃层-completed-节点的-channel-输出丢失--adv-1)）——崩溃层 channel 输出丢失 + stray COMPLETED 防护未实现
- 多 agent code review 发现 P0（4 reviewer 独立确认，见 [03-review-findings](./03-review-findings.md)）
- VT + Semaphore(20) 限流防 HikariCP 耗尽

**深挖点**：
- 崩溃恢复怎么知道从哪个 super-step 接着跑？（latestBarrier.step+1 = nextSuperStep）
- 崩溃层有节点已完成，恢复时跳过它，但它的输出没进 barrier，下游怎么读到？（replayOutputs 重放进 channel——P0 修复）
- timeout abort 后在飞 VT 还在写 checkpoint 怎么办？（stray COMPLETED，abort 时 updateStatus(FAILED)，Recovery 查到 FAILED 整体重跑崩溃层）
- Semaphore(20) 为什么是 20？（对齐 HikariCP maxPoolSize，VT 并发 50+ 时限流防连接池耗尽）
- Postgres 的 ON CONFLICT 怎么保证幂等？（`DO UPDATE WHERE status<>'COMPLETED'`，COMPLETED 终态不可覆盖，FAILED/IN_PROGRESS 可升级）

**反问准备**：
- 面试官问「这个恢复机制和数据库事务恢复有什么区别」——答：DB 事务是 ACID 原子单元，AgentFlow 的 super-step 是「逻辑事务」——节点级 checkpoint 是部分提交（防 LLM 重复计费），barrier 是逻辑提交点。崩溃恢复要处理「部分提交的节点如何与未提交的 barrier 对齐」，比 DB 事务恢复多一层。
- 面试官问「还有什么没做好」——主动暴露 known gap（见 [03-review-findings Residual Risks](./03-review-findings.md#已知未修的-residual-risks记录备查非合并阻塞)）：PG 零集成测试、版本检查依赖 U8、并发 recovery 无锁。主动暴露比假装完美可信。

---

## U14 — API 鉴权 + 凭证管理

**可讲故事**：
- `ApiKeyAuthFilter`（OncePerRequestFilter，SHA-256 hash）防 IDOR
- `WorkflowOwnershipChecker` per-workflow 所有权校验
- `CallerToolAllowlist` per-caller tool 授权（YAML 解析期校验节点引用的 @Tool）
- `CredentialManager` LLM 凭证从 env 读取，禁止 yml 硬编码（启动期检测）
- `PromptRedactionFilter` 正则脱敏 API Key/手机号/身份证

**深挖点**：
- API Key 为什么存 SHA-256 hash 不存明文？（防 DB 泄露后凭证暴露）
- IDOR 是什么？（Insecure Direct Object Reference——API Key A 能访问 B 的 workflow，靠 ownership 校验防）
- 凭证硬编码检测怎么做？（CredentialManager 启动时扫 application.yml，含明文 api-key 则 fail-fast）

**反问准备**：暂无。

---

## U9 — Mock LLM 模式

**可讲故事**：
- `MockAgentFunction` 从 YAML `mock_response` 读预设响应，支持 `${channel}` 占位符替换，零 LLM 成本
- ce-code-review 发现 `appendReplacement` 把 `${nonexistent}` 当 group 引用 → `quoteReplacement` 修复
- 跳过 MockAdvisor（plan 列的，但 mock 不走 advisor 链，判断为过度设计）

**深挖点**：
- record 加字段是 binary-incompatible 的，怎么保证不漏改调用方？（全局 grep 构造点，编译期 enforce）
- `${channel}` 占位符怎么验证上下文传递？（MockAgentFunction 读 Input.context()，正则替换 → 与 SpEL 语义等价）

**反问准备**：暂无。

---

## U10 — 主 Demo：供应商风险评估（★面试重点★）

**可讲故事**：
- 3 专家 Agent 并行（财务+合规+声誉）→ Supervisor 汇总，BSP fork-join 拓扑
- mock 模式零 LLM：mock_response 预设数据 + 占位符替换 → 汇总产出 JSON riskLevel
- 编程式组装引擎：`new BspEngine()` + `new Parser()` → 证明引擎「原子可用」
- Channel 名 = nodeId 便捷约定：MockAgentFunction.of(content) 无 channelWrites，引擎写 channel=nodeId
- 端到端测试 4 验收场景（完整流程 + channel 传递 + DAG 分层 + Recovery）

**深挖点**：
- 3 并行 Agent 怎么保证互不可见？（BSP 只读快照，同层节点各自 buffer，barrier 后 Reducer 合并）
- 汇总怎么读三路输出？（三路写独立 channel，汇总 mock_response 的 `${channel}` 从 context 读值替换）
- 为什么编程组装不用 @EnableAgentFlow？（v4.3 解耦：引擎原子可用，Starter 是封装）

**反问准备**：
- 面试官问「这个 Demo 和真实 AI Agent 区别」——答：Demo 验证引擎调度能力（并行/barrier/Reducer/Recovery），Agent 智能力由 LLM 提供，AgentFlow 负责调度——「引擎」和「Agent」的分工。

---

## 跨单元：工程化能力（★面试加分项★）

**可讲故事**：
- **Maven 多模块**：parent + core/adapters-spring-ai/api/starter，5 模块 reactor
- **JaCoCo 80% 门禁**：从 U1 起强制，覆盖率不达标 verify 失败
- **CI/CD**（U0）：GitHub Actions push/PR/每日触发 + Sonar + Docker
- **多 agent code review**：ce-code-review 10 persona reviewer 交叉验证
- **文档驱动开发**：两轮 ce-doc-review 闭环 + 5 卡点拍板，0 动工阻塞

**深挖点**：
- 为什么多模块？（职责隔离：core 不依赖 Spring AI，adapter 收敛 LLM 调用，api 是 REST，starter 是自动配置）
- 80% 覆盖率怎么保证不注水？（JaCoCo INSTRUCTION 维度，BUNDLE 级，非行覆盖；review 时看断言质量不是数字）
- code review 的多 agent 怎么跑？（10 个 persona 并行，cross-reviewer agreement 提权，confidence anchor 门控）

**反问准备**：
- 面试官问「你怎么衡量代码质量」——答：三层——`mvn verify` 绿（编译+测试+覆盖率）+ 多 agent code review（逻辑/安全/混沌/数据迁移多视角）+ JaCoCo 80% 门禁。不只是「测试通过」，是「多视角审查 + 覆盖率门禁 + review 闭环」。
