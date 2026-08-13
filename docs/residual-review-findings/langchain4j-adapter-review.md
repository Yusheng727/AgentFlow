# LangChain4jAgentAdapter ce-code-review 残留项（需人工决策/后续）

> 来源：`ce-code-review`（2026-08-11，10 评审，默认模式），对应功能 commit `f4651b2` + 评审修复 commit `e86059e`。
> 4 项已应用（见 `e86059e` commit message）；以下为本轮**判定延后/需人工决策**的项 + 共享限制。
> 更新日期：2026-08-11。

---

## A. 本轮已应用（不在此文档，详见 `e86059e`）

工具循环截断 → FatalException（P1）、`Thread.interrupted()` 中断检查（P2）、`AgentFlowMetrics` 记账钩子（P2）、`output_schema` 不支持 warn（P2）、工具错误串转义 + `@Tool` null 兜底。

---

## B. 需人工决策 / 延后项（评审未自动改，留作 v1.1 后续）

### B1. [P2/manual] ✅ **已解决（2026-08-12）** 工具异常详情泄漏给模型（SEC-1）
- **现状（已解决）**：LangChain4j 的 `DefaultToolExecutor.execute()` 在 @Tool 抛异常时捕获 `InvocationTargetException` 并直接 **`areturn cause.getMessage()`**（把原始异常消息当字符串返回给模型、**不抛出**）——所以外部包装它根本拦不到异常。
- **修复**：弃用 `DefaultToolExecutor`，新增 `LangChain4jAgentAdapter.SafeToolExecutor implements ToolExecutor`：自持 bean+method **自行反射 invoke**，在异常边界把真实 cause（可能含 DB 连接串/文件路径/内网地址）只写服务端日志、返回泛化 `{"error":"tool execution failed"}` 给模型——断开「敏感内部细节 → 模型 content → workflow channel/UI」外泄链。参数处理对齐 DefaultToolExecutor 语义（@ToolMemoryId 透传、String/primitive 强转、record 走 Jackson convertValue；void→"Success"、null→"null"、非 String→JSON）。
- **覆盖**：`LangChain4jAgentAdapterTest` +5（抛异常不泄漏单测/端到端工具循环不泄漏 + typed 参数渲染 + ToolMemoryId + 畸形 JSON）——`SafeToolExecutor` 覆盖率 37%→93.3%。
- **全仓 `mvn verify` 10 模块绿 + JaCoCo 达标**。

### B2. [P2/manual] ✅ **已解决（2026-08-11，`18978e8`）** ErrorClassifier 不识别 `dev.langchain4j.*` 异常（M2 + REL-2）
- **现状（已解决）**：core `ErrorClassifier` 新增 `composed(ErrorClassifier...)` 组合分类器；`LangChain4jAgentAdapter` 用它注入 `cause instanceof dev.langchain4j.exception.RetriableException`——LC4j 自带的可重试标记覆盖 RateLimit/InternalServer/Timeout 子类，NonRetriable 子类（Authentication/InvalidRequest/ModelNotFound）正确判 fatal。LC4j 网络/限流/超时不再被误判 Fatal 不重试。
- **M2 一并解**：core `defaultClassifier` 移除 `org.springframework.web.client.` 前缀，`SpringAiAgentAdapter` 注册 `composed(springPrefix)`——框架知识全部移出 core，core 保持框架无关。
- 实现细节点：`RetryPolicy` 默认分类器只对已映射的 TransientException/FatalException 生效（适配器先 map 再抛），故 spring 前缀移除不影响引擎重试路径。
- 测试：`ErrorClassifierTest` +4（composed 组合/基础规则/无框架）+ `LangChain4jAgentAdapterTest` +2（RateLimit→Transient、Authentication→Fatal）。
- **后续 review 补丁（`bf09771`）**：对 B2 的 ce-code-review 抓到 P1——真实 LC4j `RateLimitException` 是 marker-外层包裹 `HttpException`，适配器 unwrap 剥掉 marker 后 `instanceof RetriableException` 落空 → 真实 429 仍 Fatal 不重试（测试用 cause-less 构造器给了假绿）。修复：`ErrorClassifier.toExecutionException()` 沿 cause 兜底 + 适配器传原始异常。另补 Spring 前缀回归测试（7 评审收敛的零覆盖缺口）。详见 `developer-notes/03-review-findings.md` B2 review 节。

### B3. [P2/manual] ✅ **已解决（2026-08-12）** 继承/接口上的 `@Tool` 方法不注册（ADV-2）
- **现状（已解决）**：`collectTools` 只扫 `bean.getClass().getDeclaredMethods()`（不含继承/接口）——工具放基类/接口时模型调用 → unknown tool error → 可能进截断/失败路径。
- **修复**：新增 `collectToolMethods(Class)` 全层级遍历（本类 + 基类 + 接口含父接口），配合 `walkHierarchy` + 签名去重（`LinkedHashMap`，类先于接口/基类 → 具体实现优先、同一逻辑方法不重复注册）。**关键取舍**：不用 `getMethods()`（会丢非 public @Tool——本项目工具多包私有，如 `SafeToolExecutor` 测试的工具），故逐层用 `getDeclaredMethods()` 保留非 public + 覆盖继承面。
- **测试**：`LangChain4jAgentAdapterTest` +2（基类 `@Tool` 注册执行 / 接口 `@Tool` 注册执行，驱动工具循环断言结果回填）；原有包私有工具测试全保持绿。
- **全仓 `mvn verify` 10 模块绿 + JaCoCo 达标**。

### B4. [P2/advisory] 两适配器 `mapException` 逐字重复（M1）
- **现状**：`SpringAiAgentAdapter` 与 `LangChain4jAgentAdapter` 各一份 5 行 `mapException`。
- **判定**：只有 2 个消费者，过早抽共享抽象不一定划算（M3 评审自身也提示 NodeTrace 前导同样延后抽象）。留待消费者 ≥3 或引擎增加适配器时再收敛到 `ErrorClassifier.classifyAndWrap`。

---

## C. 共享限制（非 LC4j 引入，两真实适配器共有；v1.1 后续可做）

### C1. [P2] ✅ **已解决（2026-08-12）** per-workflow `budget_*` 预算未在真实 LLM 路径强制执行
- **现状（已解决）**：R10 的 `WorkflowBudget` 经 `AgentInput.budget()` 穿线，但只有 **`MockAgentFunction`** 消费它；`SpringAiAgentAdapter` 与 `LangChain4jAgentAdapter` 都不读 `input.budget()`——YAML `budget_tokens/budget_cost` 在真实 LLM 路径不生效（防御告警只在 mock）。
- **修复（C1）**：
  - core `AgentFlowMetrics` 新增 `recordBudget(WorkflowBudget, model, promptTokens, completionTokens)` 助手——**单一真相源**：`costCalculator.cost` **纯算成本不写 token/cost counter**（避免与 Spring `TokenCountingAdvisor` 双计），累进 budget 后首次超限触发一次 `budget_exceeded`（edge-triggered，事件数 ≠ 节点数）。
  - `LangChain4jAgentAdapter`：在既有 `metrics.recordTokens` 记账处一并 `metrics.recordBudget(input.budget(), ...)`。
  - `SpringAiAgentAdapter`：新增 8-arg 构造注入 `AgentFlowMetrics` + `model`（6-arg 委托 null 向后兼容，构造点零改动），成功路径 `metrics.recordBudget(input.budget(), ...)`——真实 LLM 路径预算自此**记账/告警生效**。
  - **语义（2026-08-12 拍板，非阻断）**：per-workflow budget 是**记账 + `budget_exceeded` 告警事件**，**不**在运行中中止执行（`src/main` 无代码读 `isExceeded()` 去 halt/skip）。硬性防护由提交前 `WorkflowSubmissionGuard`（422 超预估成本/超节点）承担；运行时记账给 Grafana 观察与告警。文档勿再称「强制」。
  - `MockAgentFunction`：per-workflow 分支改用 `recordBudget`（复用同一助手，去重复逻辑）。
- 测试：`AgentFlowMetricsTest` +3（edge-triggered 一次 / 未超限+null budget / 不双记 token-cost）+ 两真实适配器各 +2（超限/未超限，`LangChain4jAgentAdapterTest`、`SpringAiAgentAdapterTest`）。
- **全仓 `mvn verify` 10 模块绿 + JaCoCo 达标**。

### C2. [P2] ✅ **已解决（2026-08-11，`9ba7271`）** LC4j 适配器无 `OutputSchemaValidator`（结构化输出恒空）
- **现状（已解决）**：`OutputSchemaValidator` **已下沉 core**（`com.agentflow.prompt`，框架无关：networknt json-schema + Jackson 3），`LangChain4jAgentAdapter` 接入 `validateWithRetry`（带反馈重试），`structuredOutput` 不再恒空——补上 KTD-7 "相同 DSL 相同结果"对价。Spring 适配器切 import 到 core，删本地类，schema 行为零回归。
- 实现细节点：core pom + root dependencyManagement 加 `com.networknt:json-schema-validator:3.0.1`（对齐 Spring AI 传递版本）；检查型 `FatalException` 穿过 `Function` 回调用 `callForSchema` 包装 + `catch(RuntimeException)` 解包原样抛。
- 测试：`LangChain4jAgentAdapterTest` +3（成功填充/反馈重试/耗尽 Fatal）+ core `OutputSchemaValidatorTest` 8 移入。

### C3. [P3] `redactor` 默认 `Function.identity()`（未脱敏）
- 两适配器默认不脱敏；生产接入时应注入 `PromptRedactionFilter` 的脱敏函数，与 Spring 路径一致。

### C4. [P3] `input.tools()` 两适配器都不读（沿用构造器注入 toolBeans）
- pre-existing（Spring 同款）；per-node 运行时工具过滤留待引擎支持。

---

## 建议优先序（若要继续 v1.1）
1. ~~**C2**（OutputSchemaValidator 下沉 core + LC4j 接上）~~ → ✅ 已办（2026-08-11，`9ba7271`）。
2. ~~**B2 + M2**（LC4j 异常分类 + 框架知识移出 core）~~ → ✅ 已办（2026-08-11，`18978e8`）。
3. ~~**C1**（真实路径预算挂钩）~~ → ✅ 已办（2026-08-12，补 R10 在真实 LLM 的闭环）。
4. ~~**B1**（工具异常泄漏模型）~~ → ✅ 已办（2026-08-12，`SafeToolExecutor` 自持 invoke + 泛化错误）。
5. ~~**B3**（继承/接口 `@Tool` 不注册，ADV-2）~~ → ✅ 已办（2026-08-12，`collectToolMethods` 全层级遍历 + 签名去重，保留非 public）。
6. **C3 / C4**——均非代码阻断：C3（默认脱敏 identity）偏部署决策；C4（`input.tools()` 运行时过滤）牵动引擎层，doc 已标 Deferred。**至此 v1.1 的 P2 代码项（B1/B2/B3/C1/C2/M2）已全部闭环，剩余仅非代码即阻断项。**
