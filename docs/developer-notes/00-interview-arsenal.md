# 面试弹药总览

> 面试前一晚扫一遍。每个单元列出「能讲的故事 + 面试官可能深挖的点 + 你可以反问的点」。
> 故事要从 [02-bugs-and-fixes](./02-bugs-and-fixes.md) / [03-review-findings](./03-review-findings.md) / [01-implementation-rationale](./01-implementation-rationale.md) 里提取，这里只放索引和「钩子」。

---

## 自我介绍 30 秒版

「AgentFlow 是我从零复现的 Java 原生 Multi-Agent 编排引擎：YAML 声明工作流，BSP 执行模型驱动多 Agent 协作，带两级 Checkpoint 崩溃恢复。技术栈 Java 21 Virtual Threads + Spring Boot 4.1 + Spring AI/LangChain4j 双适配器 + PostgreSQL，15 个实现单元从 DSL 解析、引擎、容错、可观测到 API 安全，每一步都过 `mvn verify` + JaCoCo 80% 门禁 + 多 agent code review。」

「我最想讲两点：一是 KTD-7 可移植性——两个框架适配器（Spring AI / LangChain4j）收敛在同一个窄表面，换框架只动适配器、上游无感知；二是**闭环习惯**——review 抓到『测试全绿但生产不生效』的预算记账缺口，我把它修掉，再用真实 DeepSeek key 把 per-workflow 预算、工具执行、指标监控整条链路端到端跑通」（凭证安全、真实 token 落 Grafana）。

**深挖钩子**（等面试官问，再展开）：BSP 是什么 / 为什么从零复现 / Virtual Threads 怎么用 / Checkpoint 怎么恢复 / 双适配器如何保证可移植性 / 真实 LLM 端到端怎么验证 / code review 怎么做。

---

## 完整面试故事稿（3-5 分钟：讲透整个项目）

> 适用场景：「介绍一下你的这个项目」「讲一个你最有成就感/最难的事」。按**起→承→转→合**讲，每段一个钩子，讲完停，等面试官接。

### 起：它是什么、为什么做（30 秒）
「这是我**从零复现**的 Java 原生 Multi-Agent 编排引擎——YAML 写工作流，BSP 模型驱动多个 Agent 像微服务一样协作，支持崩溃恢复。选择从零而复不是填补生态空白（LangGraph4j 已存在），而是为了证明**后端工程的深度**：DSL 三层校验、BSP 执行引擎、两级 Checkpoint、容错、可观测、API 安全，15 个实现单元，每个都过 `mvn verify` + JaCoCo 80% 门禁 + 多 agent code review。」

### 承：核心难点我怎么做（1-2 分钟）
「引擎层最难的是 **BSP 并发执行与崩溃一致性**。
- 并发：Virtual Threads 并行跑一个 super-step 的节点，`CompletableFuture.allOf` 做 barrier——最快也要等最慢；同一层节点用**只读快照**保证互不可见（`Map.copyOf`），从根上避免共享可变状态。
- 一致性：**两级 Checkpoint**（节点级防 LLM 重复计费 + barrier 级）。崩溃恢复有个经典坑——`COMPLETED` 节点在崩溃层可能没写 channel，我查 `next super-step` 重新定位，还加了『stray COMPLETED 防护』，防止把半成品当成功。
- 架构约束：把所有框架调用**收敛在适配器窄表面**（KTD-7），CM1 换框架只动一个类。」

### 转：最出彩的一段——闭环一件『看似完成实则没生效』的事（2 分钟）
「我最想讲的是一个**闭环习惯**，它正好命中『测试全绿 ≠ 生产生效』这个坑。
- 我做 per-workflow 预算：YAML 声明 `budget_{tokens,cost}`，运行时累加，超限记 `budget_exceeded`。
- **10 个 persona 的 code review** 抓到：预算只在**成功路径记最后一次** token——schema 校验重试（2-3 次真实付费）只留末次，重试花的 2/3 成本被静默剔除；还有个更隐蔽的，两个真实适配器**根本没有被接进任何可运行装配**（demo-api 全是 mock fallback），等于能力写了、部署从不生效。
- 修复分两步：① 把记账从『成功路径收尾』改成**每轮真实调用即记**，失败/重试轮自然进预算；② 补**生产接线**——用真实 DeepSeek key 把 REST→DSL→引擎→适配器→LLM→Prometheus 整条链路端到端跑通，抓到真实 token 3275、成本 7.68e-4 USD、2 节点 20 秒耗时落进 Grafana。key 全程只走 env，从不落盘。」
- （可选加分）「这个过程中还印证了『复用而非重造』：我一度手写参数强转想对齐框架，结果字符串数字会 CCE，后来直接复用 Jackson 语义就稳了。」

### 合：一句话收束 + 留白（30 秒）
「所以 AgentFlow 对我不是写了个 demo，而是一整套**工程闭环**：设计→实现→多 agent 审查→修→真实环境验证→沉淀成可复述的教训。如果继续，我想做 v2 的运行时条件分支，把静态 DAG 变成真正动态的 agent 路由。」

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

## U6 — 调试体验（Dry-run + Diagnosis）

**可讲故事**：
- `DryRunEngine` 复用 BSP 拓扑，内置 `DryRunMockAgentFunction`（core 本地，不依赖 adapter），不发 LLM → 返回每步预期 input/output schema
- `DiagnosisService` 分析 ExecutionTrace 识别 5 类问题：（连续超时/Token 异常消耗/SpEL 解析失败/Channel 缺失/节点重复执行）+ 每类输出修复建议
- `StructuredLogger` JSON 格式日志（workflowId/nodeId/durationMs/token/status）
- 设计权衡：DryRunMockAgentFunction 放 core 而非复用 U9 MockAgentFunction——消除 core→adapter 反向依赖

**深挖点**：
- Dry-run 和 mock 模式区别？（Dry-run 不要求 mock_response，无则自动生成 schema；mock 需配 mock_response，返回具体内容。都不调 LLM）
- Token 异常怎么识别？（遍历 SUCCESS 节点，标记 token > 均值 ×3 且 > 100 阈值——避免低 token 节点因均值低被误报）

**反问准备**：暂无。

---

## U7 — 可观测性（★面试重点★：trace 穿线 + 成本核算 + 多 agent review）

**可讲故事**：
- **5 Micrometer 指标**：`workflow.executed`（执行计数）/`node.duration`（节点耗时 Timer）/`tokens.consumed`（token Counter）/`workflow.cost.estimated`（成本估算）/`workflow.cost.budget_exceeded`（预算超限）——Grafana Dashboard 直采
- **trace 穿线难题（KTD-2）**：MockAgentFunction 是无状态单例、BspEngine 不持有 trace——mock 模式下 TraceController 返回空树。解法：`ExecutionTraceRegistry`（workflowId→trace 集中存放）+ BspEngine 5-arg 构造器注入 + AgentInput 第 9 字段透传 trace → MockAgentFunction/SpringAiAgentAdapter 从 `input.trace()` 取 trace 写 NodeTrace。traceRegistry=null 整条链路 no-op，旧构造器保留向后兼容
- **成本核算（KTD-3）**：TokenCountingAdvisor 扩展 3-arg 构造器委托 AgentFlowMetrics 记成本（不新建类）。`CostCalculator` 三层定价：代码默认价 → classpath `agentflow-cost-pricings.json` 覆盖 → 程序化 `override()` 最高优先级。warn-once 去重 + 缺文件不崩（R4 规避硬编码过时）
- **ce-code-review 闭环**：11 reviewer 并行审，2 个 P0 + 4 个 P2 全修。最严重的 P0（recoverAndExecute 不接 trace）被 **4 个 reviewer 独立确认**（adversarial + correctness + reliability + agent-native）——和 U5 的两个 P0 一样 4 票确认

**深挖点**：
- trace 穿线为什么不用 ThreadLocal？（Virtual Threads 跨任务边界 ThreadLocal 脆弱；用 record 字段显式传递更可靠，OQ-3 决议延伸）
- 为什么 AgentInput 加字段而非新建 context 对象？（record 扩展是项目第 4 次同模式，有 grooved convention；v2 若暴露公共 SPI 再考虑 TraceContext carrier）
- recoverAndExecute 的 P0 是什么？（恢复路径硬编码 null trace，恢复成功后 checkpoint=SUCCESS 但 registry 返回旧 FAILED trace——状态分裂，和 U5 ADV-1 同类：trace 没穿进恢复路径）
- 单价表为什么放配置文件？（模型单价变化频繁，硬编码会过时——R4 风险。三层定价让运维改 JSON 不改代码）
- mock 模式写 trace 破坏单例吗？（不破坏——per-workflow 隔离通过 AgentInput 传，不是 MockAgentFunction 自身状态）

**反问准备**：
- 面试官问「你怎么做可观测性」——答：不是只加日志，是三层——Micrometer 指标（Grafana 直采）+ 结构化 trace（ExecutionTrace 树，TraceController REST 查）+ 成本核算（token×单价表，预算告警）。trace 穿线解决了 mock 模式不可观测的盲区。

---

## U11 — 合同审核串行流水线 Demo（对比 U10 并行）

**可讲故事**：
- 4 节点串行链（合同解析 → 法律风险 → 合规建议 → 最终报告），每步 `mock_response` 用 `${previousStep}` 占位符引用上一步输出——验证 BSP 串行依赖链 + 上下文逐级传递
- 4 super-step 各 1 节点（最长路径分层），与 U10 的 3 并行 + 1 汇总形成拓扑对比
- **附带修复连字符 channel 正则**：MockAgentFunction PLACEHOLDER `[\\w.]` → `[\\w.-]`，让 `${contract-parse}` 这类带连字符的 channel 引用可解析（channel=nodeId，连字符是项目命名约定，U10 用了连字符但汇总节点没引用上游所以没暴露）

**深挖点**：
- 串行 vs 并行拓扑的 BSP 区别？（串行每层 1 节点 = N super-step；并行同层多节点 = 1 super-step。串行验证上下文逐级传递，并行验证 channel 隔离）
- 连字符正则 bug 怎么发现的？（U11 要 `${contract-parse}` 引用上一步，发现不解析——U10 用连字符但 aggregate 的 mock_response 没引用上游，bug 潜伏。U11 是真正用占位符引用连字符 channel 的第一个 demo）
- 失败隔离怎么验证？（内联 YAML 把 legal-risk 的 mock_response 去掉触发 MissingMockResponseException，断言前置 checkpoint 已存 + 工作流 FAILED）

**反问准备**：暂无。

---

## U12 — 投资分析双层 fork-join Demo（复杂混合拓扑）

**可讲故事**：
- 6 节点 4 super-step 双层 fork-join：step0（公司财报 + 市场数据并行）→ step1（可行性分析串行）→ step2（风险评估 + 收益预测并行）→ step3（投资裁决汇总）
- 验证 `DAGLayerer.computeSuperSteps` 最长路径分层对复杂混合拓扑的泛用性——用 `containsExactlyInAnyOrder` 断言 4 层分层
- 最终汇总引用前 3 层全部 5 个输出，验证 channel 隔离 + 跨 super-step 上下文传递

**深挖点**：
- 双层 fork-join 怎么分层？（最长路径：company-finance→feasibility→risk→decision = 4 层；并行节点同层。DAGLayerer 算 `level[v]=max(level[u])+1`）
- channel 隔离怎么验证？（step0 的 company-finance 和 market-data 各自 channel 独立，step1 的 feasibility 同时引用两者不串——断言 feasibility 含 company-finance 的"营收"且含 market-data 的"PE"）
- 为什么 U12 一开始用下划线后改连字符？（初版用下划线绕开连字符正则 bug；ce-code-review 后 U11 已修正则，project-standards reviewer 指出下划线偏离项目约定，改回连字符对齐 U10/U11）

**反问准备**：暂无。

---

## 前端 React UI（交付门面，5 Tab）

**可讲故事**：
- **技术栈**：React 18 + TypeScript + Vite + Tailwind CSS，按 `prototype-final.html` 原型转 5 Tab 组件
- **5 Tab**：Dashboard（KPI 行 + 三列看板 + 最近执行表格）/ Submit（YAML 编辑器 + 配置表单 + 提交/Dry-run）/ WorkflowDefinitions（定义卡片网格）/ PipelineView（BSP Pipeline super-step 可视化）/ DiagnosisPanel（KPI 摘要 + 诊断结果）
- **真实 API 优先 + mock fallback**（KTD-1）：`api.ts` 封装 fetch，先调真实 `/api/workflows` 等，后端不可达时降级 `mockData`（setTimeout 模拟）——保证 UI 独立可用不白屏
- **YAML 编辑器**：contenteditable + 语法高亮 + 行号 + 实时校验（缺 nodes/agentflow 段警告）
- 与后端 REST 契约对接：`/api/workflows`（POST 202 异步 + GET status + POST retry）+ `/api/workflows/{id}/trace`（U7 TraceController）+ `/api/diagnosis`

**深挖点**：
- 为什么真实 API 优先 + mock fallback？（演示时后端可能没起，UI 不能白屏；mock fallback 让 UI 独立可演示。KTD-1 决策）
- Pipeline 怎么可视化 BSP？（按 super-step 分组渲染节点卡片，barrier 用分隔线——体现"同层并行 + barrier 同步"的 BSP 语义）
- 为什么不用 Next.js/SSR？（这是个内部工具门面，SPA 够用；Vite 启动快，不引入 SSR 复杂度）

**反问准备**：
- 面试官问「前端怎么和后端协作」——答：UI 串行先行作交付门面，对接已有 REST API（U14 鉴权 + U7 trace + U6 diagnosis）。真实 API 优先 + mock fallback 保证 UI 独立可用。YAML 编辑器实时校验 + Pipeline 可视化 BSP super-step——让工作流拓扑可见。

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

---

## 档 B 收尾 + v1.1（2026-08，最鲜活弹药）

### WorkflowSubmissionGuard（安全缺口 #2）

**可讲故事**：
- 提交时预防性守卫：节点数 / 预估成本超上界 → 422 拒绝，在 initWorkflow 前（不产生脏记录）
- 成本估算是预防性近似（prompt 长度估 token + 每节点基准 500），非记账——讲清楚"防超载 vs 精确计费"的边界
- `model/maxNodes/maxCostUsd` 任一 null 即禁用——安全默认可开关

**深挖点**：
- 为什么是提交时不是 post-hoc 告警？（post-hoc 只能事后止损，起资源前拦才治本）
- 拦截点在鉴权/授权之后、持久化之前，为什么？（守卫拒绝不该留执行记录）

### R10 per-workflow 预算（WorkflowBudget）

**可讲故事**：
- `budget_tokens/budget_cost` 声明在 YAML `agentflow:` 段，`AgentInput.budget()` 穿线
- **edge-triggered** 语义：只在首次跨过上界记一次 `budget_exceeded`，不按节点数重复——告警事件语义 vs 计数
- 线程安全（synchronized，超步内多节点并行记账）

**深挖点**：
- 为什么不用全局阈值？（per-workflow 才是语义正确：每个工作流超自己的预算）
- 为什么 edge-triggered？（全局 checkBudget 每次调用自增，事件数=节点数，不是"跨过预算"）

### LangChain4jAgentAdapter（KTD-7 可移植性实证）★最强叙事

**可讲故事**：
- 第二个框架适配器，**依赖面仅 core + langchain4j、零 Spring AI**——从构建级证明"换框架只动适配器"
- 窄表面对齐 Spring 适配器：SpEL → ChatModel → usage → AgentOutput，metadata schema 逐字段相同
- 裸 ChatModel 无内置工具闭环 → 手写工具执行循环（≤5 轮防死循环，usage 跨轮累加计费）
- `SpelPromptResolver` 下沉 core：框架无关件单一真相源，不重复造轮子

**深挖点（面试官最可能问）**：
- 「为什么不做同一个库里的第二个适配器？」——框架隔离，避免把 Spring AI 拉进 LangChain4j classpath 掺水
- 「怎么证明可移植性是真的？」——构建级：新 module 的 pom 依赖里没有 spring-ai；运行时：两适配器独立跑通冒烟测试
- 「裸 ChatModel 为什么手写工具循环？」——LangChain4j 内置闭环在 AiServices（不同范式），要跟 Spring 适配器同构就不该切 ApiServices

### 本轮 P1 bug（有界循环的「终止态表达」）★工程 rigor 展示

**可讲故事**：
- 工具循环静默成功 bug：上限到点直接返回 null-content，一个 broken 的 agent 循环被记为绿色 SUCCESS + 丢弃已排期工具
- **两个 reviewer 独立抓到同一处**（adversarial + correctness）→ cross-reviewer agreement 提权 confidence 100
- 修复：超限判 FatalException，不伪装成功——讲「有界循环不只保证终止，还要保证终止态表达」

**深挖点**：
- 「这种 bug 在 agent 编排里为什么危险？」（坏状态伪装成功，下游 SpEL 才炸，根因藏在 warn 日志）
- 「为什么不用截断标志而是直接失败？」（截断的 token/副作用已不可回滚，安静成功比显式失败代价更高）

### C1+B1+B3 ce-code-review 复审（2026-08-12，10-persona）★工程 rigor

**可讲故事**：
- 对已合 main 的三个 commit 做 10 个 persona 的复审，抓到 7 条 P2，应用 4 条 high-conf 修复
- **预算「只记成功路径末次」低估 ≤3x**：schema 校验重试是多次真实付费，`AtomicReference last` 只留末次 → 重试花的 2/3 成本被静默剔除（正对「测试绿 ≠ 生产生效」的又一个形态）。修复：把记账从"成功路径收尾"改成**每轮真实调用即记**（`metricAndBudget`），失败/重试轮自然都入预算
- **自写 coerce 想对齐框架却漏了 CCE**：`{"count":"3"}` 字符串数字 → `((Number)value).intValue()` 抛 ClassCastException，而 Jackson `convertValue("3", int.class)` 能转。修复=删分支、复用框架语义——**「尽量复用而非重造易碎强转」**
- **SafeToolExecutor 吞 InterruptedException 不恢复标志**：cancel 到达被清 flag → 上层 REL-1 中断失效，已取消节点再开付费轮。修复=root cause 为 Interrupted 时 `Thread.currentThread().interrupt()`
- **重名 @Tool**：B3 故意枚举基类/接口后，同名重载/共享基类会给模型重复 ToolSpecification 名 → 只首次 add

**深挖点（面试官最可能问）**：
- 「为什么 7 条里 4 条都是『我的修复引入的』？」——闭环：正是对自己上一轮代码的独立复审才有这个价值；被多 persona 交叉命中的 conf 提到 100
- 「预算语义是记账还是强制？」——**拍板为记账/告警非阻断**：运行中不中止（R10 本意是告警），硬性防护归提交前 `WorkflowSubmissionGuard`（422）。讲清楚「观察 vs 治理」的分层

### 档 1 真实 DeepSeek 端到端（2026-08-13）★最硬闭环

**可讲故事**：
- 评审残留「demo-api/starter 从不构造真实适配器 → C1/B1/B3 部署未生效」（能力已具备、wiring 缺失）
- 补生产接线：demo-api 加真实 agent 条件装配（`agentflow.real.enabled` + env `DEEPSEEK_API_KEY`），**凭证只从 env 读、缺失启动失败**，其他 agent 名回落 mock 兼容
- **真实端到端跑通**：REST `POST /api/workflows` → DSL → BspEngine → `LangChain4jAgentAdapter`（OpenAI 兼容 → DeepSeek `deepseek-chat`）→ 2 节点串行逐级传参 → SUCCESS
- **真实指标落盘**（`/actuator/prometheus`）：`tokens_consumed_total{agent="deepseek",model="deepseek-chat"}=3275`、`cost_estimated_total{model="deepseek-chat"}=7.68e-4 USD`、`node_duration_seconds_sum=20.47s`（2 节点）、`workflow_executed_total{status="success"}=1`——**真实 token/成本/耗时**，非 mock 模拟值
- 全程 key 只走 env，未入文件/commit/日志——**凭证安全**也是可讲的点

**深挖点（面试官最可能问）**：
- 「能力早就有了为什么不直接跑？」——仓库里根本没有生产接线路径，mock fallback 包住了所有 agent 名；这正是评审抓的「test 全绿但生产不生效」根源，档 1 补的就是这条可部署路径
- 「为什么端到端要真实 key？」——mock 模拟 token 永远不能证明 KTD-7 第二适配器对接真实 OpenAI 兼容 provider；真实 key 才拿到 `usage`/`TokenUsage` 走通 C1 逐轮记账
- 「凭证安全怎么保证？」——`CredentialManager` 规范：env 注入、启动校验、检测 yml 硬编码占位符即启动失败

### C3 默认脱敏 + C4 per-node 工具过滤（2026-08-13 收尾，residual 全闭环）

**可讲故事**：
- **C3 默认脱敏（安全加固）**：两适配器不注入 redactor 时默认 `PromptRedactionFilter::redact`（core 静态：sk-/Bearer/手机号/身份证）——原 `Function.identity()` 让 trace 摘要不脱敏，改成默认脱敏、可显式覆盖。一句安全默认值：**宁默认保守，别默认裸奔**
- **C4 per-node 工具过滤**：两适配器读 `input.tools()` 只注册/注入被请求的工具——LC4j **方法级**（对着 B3 注册面精确过滤），Spring **bean 级**（`spec.tools` 按对象注册的框架粒度差异，如实说）。副作用是**最小暴露面**：节点只用哪个工具，模型就只见哪个工具

**深挖点**：
- 「为什么默认脱敏而不是让调用方显式传？」——安全默认值：identity 意味着"忘了配就裸奔"，出事故后难追溯；默认脱敏把失败模式倒过来
- 「LC4j vs Spring 过滤粒度为什么不同？」——LC4j 反射可精确到方法；Spring AI `spec.tools(Object...)` 按 bean 注册全部 @Tool——**诚实讲框架边界，比假装一致更可信**

---

## 08-07 Grafana 可观测全闭环 + PG 收尾（详见 06）

**可讲故事**：
- 「面板定义好了但全是空」→ 拆三层缺口：**指标只在测试里记（引擎从不 hook）**、**mock 不出 token/成本**、**没有 Prometheus 出口**——逐个闭环后 `/actuator/prometheus` 端到端起服验证 6 面板全有数据
- `publishPercentileHistogram` 暴露 `_bucket` 序列 → histogram_quantile 算 P50/P95/P99
- 真 PG 集成测试用 Failsafe `*IT`（CI 有 PG 全跑 / 本地无 PG 跳过）+ H2 建兼容表跑真实 SQL
- 兑现了 05 里"待补"的 Postgres `listByCreatedBy`——**并主动承认那处文档没同步勾掉的疏漏**（诚实 + 工程 rigor）

**深挖点**：
- 「怎么知道面板会空、空在哪段？」（指标管线：定义→引擎钩子→exporter→Prometheus 抓→Grafana，任一段断就空；要端到端起服看，不是看测试）
- 「为什么接口有了数据还是空的？」（define 不等于 hook——`recordWorkflowExecuted` 之前只有测试调，引擎不调就没样本）

---

## HITL 审批 + R22 列加密 + RAG demo（2026-08，feat/hitl-r22-rag）★最新鲜弹药

**一句话**：把 v2 的「条件分支/迭代收敛」再扩成 **「中断 → 外部人工审批 → 恢复执行」** 全链路（HITL）+ 敏感列静态加密（R22）+ RAG 扩展点实证（KTD-6）。

**可讲故事**：
- **HITL 全链路**（U4–U6）：Agent 抛 `ApprovalRequiredException` → 引擎在 barrier 暂停至 `AWAITING_APPROVAL` + 审批单（**上下文快照**含兄弟输出）落库 → REST 批准后 `approveAndResume` 重跑待批节点注入决策、续跑下游。**最出彩**：快照只含兄弟输出 → 恢复不双跑兄弟（KTD-3 防重复计费在 HITL 的镜像）；`recoverAndExecute` 拒绝误恢复 AWAITING_APPROVAL
- **decidedBy 服务端推导**（U6 安全）：请求体仅 `{decision}`，客户端 decidedBy 忽略，`decidedBy` = caller 的 X-API-Key hash——防审批审计身份伪造（review P1）
- **R22 列加密**（U7）：`AesGcmColumnEncryptor`（AES-256-GCM，`AESGCM:` 自描述前缀 + **legacy 明文行兼容**）+ `fromEnvStrict()` 生产 fail-closed（缺 key 拒绝明文落库）。引擎/DSL/InMemory 零感知——加密是存储层关注点（KTD-E1/E2）
- **RAG 扩展点实证**（U8/`demo-rag`）：`RagAgentFunction`（检索→增强→委托）+ 确定性 embedder（离线零外部服务）；`RagEngineZeroChangeTest` 用 BspEngine 直跑 `agent: rag` 零改动——**KTD-6「引擎零改动、扩展点成立」不靠白盒 hack 靠契约**

**深挖点**：
- 「BSP 在运行时动态 + 审批暂停下还能保留吗？」（暂停/恢复都走 `runRounds` 既有骨架；快照重放买回确定性——循环/审批/崩溃三条恢复路径复用同一套）
- 「怎么防审批后重复计费？」（快照只含兄弟输出不含审批节点；`firstExcluded=审批层全集`；takenEdges 预置防 saveRoutingDecisions 覆盖丢边）
- 「加密 key 从哪来？降级吗？」（只从 env `AGENTFLOW_ENCRYPTION_KEY` 读；dev 宽松 Noop、生产 strict fail-closed；key-before-first-write）
- 「RAG 为什么用这么朴素的 embedder？」（KTD-6 命题是「扩展点成立」不是向量库先进；真实 embedding 属于 InterviewCoach，分工不重复）

---

## R22 系统性扩列 + 审批中心 UI（2026-08，feat/r22-extend-approval-ui）★最新鲜弹药

**一句话**：把 R22 从「只加密 checkpoint 一处」升级为 **「敏感列系统性加密方案」**（routing_decisions + workflow_definitions 扩列 + starter 双 strict 装配 + 真 PG IT 密文证据），把 HITL 从「curl-only」升级为 **「审批中心 Web UI」**（跨工作流聚合端点 + 第 6 Tab + AWAITING_APPROVAL 独立看板列）。

**可讲故事**：
- **R22 扩列三件套**（U1/U2）：① `workflow_routing_decisions.decisions`（路由决策，V8 迁移 JSONB→TEXT）走既有 `toEncryptedJson/decryptRaw` 边界；② `workflow_definitions.definition`（**完整 DSL——prompt_template 含业务话术**）——`PostgresWorkflowDefinitionStore` 加可空 ColumnEncryptor 同一模式；③ **starter 双 strict**：生产模式注册 PG 定义存储也用 `fromEnvStrict()`，杜绝「checkpoint 加密了、定义还明文」的半吊子状态。5 处 JSONB 敏感列全覆盖 = 「系统性方案」而非 demo
- **测试环境方言坑**（U1 实录）：H2 即使 PostgreSQL 兼容模式也**不支持 `ON CONFLICT DO UPDATE`**（只认自有 `MERGE INTO ... KEY`，写探针程序实测确认）——路由决策的 upsert SQL 在 H2 跑不通。解法：H2 聚焦**读路径**（种密文行/legacy 明文行 → 解密还原），**写路径密文形态归真 PG IT**——与 node output/channel 的既有分工完全一致（那两处 INSERT 的 `ON CONFLICT...WHERE` 同样 H2 跑不通）。测试策略跟着**方言边界**走，不是硬凑 H2
- **审批中心聚合端点**（U3）：`GET /api/approvals/pending`——非 admin 经 `listByCreatedBy(caller)` 遍历只见自己的（**天然不泄漏他人审批存在性：空数组而非 403**）；admin 见全部。**精简投影纪律延续**：ApprovalCenterView 不下发 requestPayload/contextSnapshot（review P2 防敏感载荷旁路泄漏）
- **审批中心 UI**（U4）：第 6 Tab + APPROVE/REJECT 决策。**读操作 mock fallback、写操作不 fallback**——后端不可达时列表降级演示数据，但决策 POST 失败必须 toast 报错，不能静默假成功（与 retry 同纪律）
- **顺带修真 bug**：`AWAITING_APPROVAL` 在 UI 原来被 `as WorkflowStatusUi` 静默丢弃（三列分桶 lookup undefined、状态列空白）——现在独立第四列「待审批」（琥珀色）+ 卡片「去审批」按钮直达审批中心。「人机协同」的「人需要动手」在界面上大声说出来

**深挖点**：
- 「哪些列加密？为什么是这些？」（5 处 JSONB 敏感列：node output / channel / 审批载荷×2 / 路由决策 / 定义。判据 = 列内是否含**业务敏感数据**（prompt 话术、审批金额、路由上下文）；`workflow_name` 等运维元数据明文可接受——加密有边界不是越多越好）
- 「列型为什么 JSONB→TEXT？」（密文非合法 JSON；对齐 V7 先例原列转型，不加影子列避免读路径分叉；legacy 明文行 decrypt 原样返回不碎裂）
- 「聚合端点性能？」（N 工作流 N+1 查询，demo 规模可接受；SQL JOIN 单查询记 Deferred——**先说清楚边界再谈优化**）
- 「为什么空数组不是 403？」（聚合端点不知道你要看谁：非 admin 只查自己的域，查出来空就是真没有；403 反而泄漏「存在你看不到的审批」这个事实）
