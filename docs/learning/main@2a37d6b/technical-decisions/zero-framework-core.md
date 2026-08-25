# 技术决策：core 零框架 + AgentFunction 窄接口（框架知识全部外移适配器）

> 分析时间：2026-08-25 ｜ target：main@2a37d6b27e5d50687c656adbeedd2b92c0fdee92 ｜ 模式：deep-dive
> 分析范围：KTD-6/KTD-7 依赖纪律（core 不依赖任何 LLM 框架） ｜ 未覆盖/不可访问区域：无
> 状态：生效中（构建级强制）[代码已确认]

## 1. 背景与约束

- 目标支持多 LLM 框架（Spring AI 2.0 / LangChain4j 1.0），且要**证明**换框架是局部改动 [需求已确认]（KTD-7 可移植性约束）。
- 框架迭代快（Spring AI 2.0 就要求 Boot 4.1 + Framework 7 + Jackson 3）[历史已确认]（OQ-2 决议记录）。

## 2. 当前方案

**构建级隔离**：`agentflow-core` pom 零 LLM 框架依赖（只 slf4j/Jackson/networknt + 测试域 Spring）；两适配器反向依赖 core [代码已确认]（各 pom + CodeGraph import 按 namespace 验证无泄漏）。

**接口级窄化**：`AgentFunction`（execute/cancel 两方法）+ `AgentInput`（11 字段 record）+ `AgentOutput`（4 字段 record）+ 异常族（Transient/Fatal/AgentExecution/ApprovalRequired）——引擎只认这组合约 [代码已确认]（`agentflow-core/src/main/java/com/agentflow/agent/AgentFunction.java`）。

**知识级外移**（B2/M2 决议）：「哪些框架异常是 transient」这类知识由各适配器经 `ErrorClassifier.composed()` 注入；「SpEL 怎么解析」这类共享件下沉 core `com.agentflow.prompt` 单一真相源 [历史已确认]（commit `18978e8`）。

**实证物**：6 个 AgentFunction 实现（两 LLM 框架 + Mock + RAG + ApprovalGate + DryRunMock）零改动互换 [代码已确认]（CodeGraph query）。

## 3. 可确认的替代方案

- 直接依赖 Spring AI（作为唯一框架）——KTD-7 明确否决 [需求已确认]（03 决议文档）。
- LC4j 适配器用 AiServices 抽象而非裸 ChatModel——代码注释记录了否决理由（「为保持与 Spring 适配器同构的 ChatModel 窄表面而未采用」）[代码已确认]（`agentflow-adapters/langchain4j/src/main/java/com/agentflow/adapters/langchain4j/LangChain4jAgentAdapter.java#chatWithTools javadoc`）。
- 「LC4j 只依赖 core」的降级实现（无独立模块）——未发现痕迹，实现是独立 Maven 模块 [代码已确认]。

## 4. 为什么当前方案可行（代码事实论证）

- 可替换性是**构建可验证**的：`mvn -pl agentflow-adapters/langchain4j -am` 编译通过即证明该框架的全部知识被隔离在一个模块 [代码已确认]（pom 依赖面）。
- 异常分类演进不碰 core：Spring 前缀规则从 core 移到 Spring 适配器（commit `18978e8`），core 的 defaultClassifier 只认框架无关根基（TimeoutException/IOException/java.net.*）[代码已确认]（ErrorClassifier javadoc「框架无关 v1.1 B2/M2」段）。
- RAG 这种非 LLM 框架的能力也能挂进同一扩展点（RagAgentFunction 检索→增强→委托 delegate）[历史已确认]（commit `9d09e6e`「引擎层零改动、Agent 扩展点成立」）。

## 5. 正面影响

- 框架升级（如 Spring AI 2.0→2.1）只碰一个适配器类，95 个引擎 callers 零感知 [代码已确认]。
- 框架坑（mutable deque、DefaultToolExecutor 吞异常、JsonSerializer 废弃）被物理隔离在适配器文件内，注释即文档。

## 6. 代价与风险

- **能力对齐负担**：两实现并行时语义极易漂移（C1 review 抓到 LC4j 逐轮记账 vs Spring 成功路径记末次，预算低估 ≤3x）——需「相同 DSL 相同结果」对价条款约束 [历史已确认]（commit `8157e4a`）。
- 窄接口表达不了框架特有能力（如 Spring 的 advisor 链只能经构造器注入黑盒化）[合理推断]（支撑：advisor 是 List<Advisor> 透传，引擎无感知）。
- chatWithTools 手动工具循环是 LC4j 裸 ChatModel 无回调循环的被迫补课（~150 行框架本该做的事）[代码已确认]（javadoc 自述）。

## 7. 何时可能需要替换

[合理推断] 触发条件：① 出现需要框架深度特性（流式输出/多模态）的节点类型 → 接口需扩字段（AgentInput 已从 3 字段长到 11 字段，先例在）；② 只保留单框架时窄表面收益消失，但作为简历项目双框架本身是展示物。

## 8. 证据

| # | 证据 | 类型 | 支持的结论 |
|---|---|---|---|
| 1 | `agentflow-adapters/langchain4j/pom.xml` | 构建 | LC4j 模块依赖面仅 core+langchain4j |
| 2 | `agentflow-core/src/main/java/com/agentflow/agent/AgentFunction.java` | 源码 | 两方法窄接口 |
| 3 | `agentflow-core/src/main/java/com/agentflow/engine/fault/ErrorClassifier.java#L31-L38` | 源码 | composed 注入模式 + 框架无关声明 |
| 4 | CodeGraph query "AgentFunction" | 图索引 | 6 实现全集 |
| 5 | commit `18978e8`/`9ba7271` | git | 框架知识外移 + 共享件下沉 |
| 6 | commit `9d09e6e` | git | RAG 扩展点实证 |

## 9. 个人学习收获

「可移植性」这个口号我在别处见过太多，但这里是第一次看到它被做成**可构建验证的事实**——不是「我们抽象了一下」，而是「删掉这个模块引擎照样编译」。这教会我：架构性质的说服力取决于它能否被一条命令验证。第二个收获是 B1 的取证方法论：发现 DefaultToolExecutor 吞异常靠的是反编译读源码——**第三方库的异常语义是接口契约的一部分，javadoc 不写就要去字节码里找**。第三个是 C1 的对称性教训：两个平行实现最危险的漂移是语义漂移（记时点不同），不是代码漂移——需要显式的对价条款（KTD-7 "相同 DSL 相同结果"）+ 复审机制才能守住。
