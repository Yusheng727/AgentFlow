# LangChain4jAgentAdapter ce-code-review 残留项（需人工决策/后续）

> 来源：`ce-code-review`（2026-08-11，10 评审，默认模式），对应功能 commit `f4651b2` + 评审修复 commit `e86059e`。
> 4 项已应用（见 `e86059e` commit message）；以下为本轮**判定延后/需人工决策**的项 + 共享限制。
> 更新日期：2026-08-11。

---

## A. 本轮已应用（不在此文档，详见 `e86059e`）

工具循环截断 → FatalException（P1）、`Thread.interrupted()` 中断检查（P2）、`AgentFlowMetrics` 记账钩子（P2）、`output_schema` 不支持 warn（P2）、工具错误串转义 + `@Tool` null 兜底。

---

## B. 需人工决策 / 延后项（评审未自动改，留作 v1.1 后续）

### B1. [P2/manual] 工具异常详情泄漏给模型（SEC-1）
- **现状**：LangChain4j 的 `DefaultToolExecutor` **内部吞异常并回传原始消息**（"Error: <原始异常 msg>"）给模型；源码看它不走适配器 `executeTool` 的 catch。
- **风险**：@Tool 抛的异常若含敏感内部细节（SQL/文件路径/DB 连接串），会被模型在 `content` 里复述 → 流到 workflow channel/UI。`redactor` 只作用于 NodeTrace，此路径不改写。
- **判定**：这是**框架标准行为**（等价 Spring 工具错误处理），且暴露在 U14 ApiKeyAuth 之后——v1.1 基准不阻断，但值得定策略。
- **修复方向**：自定义 `ToolExecutor`（继承/包装），统一返回通用错误串，真实原因只进服务端日志；或接受现状 + 文档标注。
- **决策人**：你（是否接受框架默认、还是上自定义 executor）。

### B2. [P2/manual] ErrorClassifier 不识别 `dev.langchain4j.*` 异常（M2 + REL-2）
- **现状**：core `ErrorClassifier.defaultClassifier()` 只判 `java.net.*` / `org.springframework.web.client.*`；LC4j 网络瞬时异常（429/5xx 包裹成 LC4j 类型）会被误判 **Fatal → 不重试**。
- **另注**：core 含 `org.springframework.web.client.` 前缀 = 框架独有知识泄漏进 core 层（对 LC4j 路径为死代码）。
- **修复方向**：在 core 加 LC4j 例外类型到 transient 清单（注意：宽前缀可能把致命错误误判临时 → 需圈定具体异常类型，如 `HttpException`/超时子类，非整个包）。
- **风险点**：动 core 分类逻辑影响两个适配器，需针对型测试。

### B3. [P2/manual] 继承/接口上的 `@Tool` 方法不注册（ADV-2）
- **现状**：`collectTools` 只扫 `bean.getClass().getDeclaredMethods()`（不含继承/接口）。
- **风险**：工具放基类/接口时模型调用 → unknown tool error → 可能进截断/失败路径。
- **修复方向**：改用 `getMethods()`（含继承 public）或层级遍历；**注意** `getMethods()` 会丢非 public @Tool（现测试用的包私有 @Tool 会被漏掉），需处理。

### B4. [P2/advisory] 两适配器 `mapException` 逐字重复（M1）
- **现状**：`SpringAiAgentAdapter` 与 `LangChain4jAgentAdapter` 各一份 5 行 `mapException`。
- **判定**：只有 2 个消费者，过早抽共享抽象不一定划算（M3 评审自身也提示 NodeTrace 前导同样延后抽象）。留待消费者 ≥3 或引擎增加适配器时再收敛到 `ErrorClassifier.classifyAndWrap`。

---

## C. 共享限制（非 LC4j 引入，两真实适配器共有；v1.1 后续可做）

### C1. [P2] per-workflow `budget_*` 预算未在真实 LLM 路径强制执行
- **现状**：R10 的 `WorkflowBudget` 经 `AgentInput.budget()` 穿线，但只有 **`MockAgentFunction`** 消费它；`SpringAiAgentAdapter` 与 `LangChain4jAgentAdapter` 都不读 `input.budget()`——YAML `budget_tokens/budget_cost` 在真实 LLM 路径不生效（防御告警只在 mock）。
- **修复方向**：给两个真实适配器加 budget 记账（在 `recordTokens`/`recordsMetrics` 处一并 `budget.record()`）。

### C2. [P2] LC4j 适配器无 `OutputSchemaValidator`（结构化输出恒空）
- **现状**：`structuredOutput` 恒 `Map.of()`，已加 warn 明示；Spring 适配器支持 schema 校验 + 带反馈重试。
- **修复方向**：把 `OutputSchemaValidator`（networknt，框架无关）下沉 core（同 `SpelPromptResolver` 方式），LC4j 适配器接上，实现完整 KTD-7"相同 DSL 相同结果"对价。

### C3. [P3] `redactor` 默认 `Function.identity()`（未脱敏）
- 两适配器默认不脱敏；生产接入时应注入 `PromptRedactionFilter` 的脱敏函数，与 Spring 路径一致。

### C4. [P3] `input.tools()` 两适配器都不读（沿用构造器注入 toolBeans）
- pre-existing（Spring 同款）；per-node 运行时工具过滤留待引擎支持。

---

## 建议优先序（若要继续 v1.1）
1. **C2**（OutputSchemaValidator 下沉 core + LC4j 接上）——补上可移植性对价，工作量可控、叙事价值高。
2. **B2**（LC4j 异常分类）——避免真实 LC4j 网络抖动被误判 Fatal。
3. **C1**（真实路径预算挂钩）——补 R10 在真实 LLM 的闭环。
4. B1 / B3 —— 看是否自定义 ToolExecutor / 层级遍历 @Tool。
