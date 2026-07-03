# U3 接手清单 — Spring AI Agent 适配器层

> 给下一个 Claude Code 会话：在 `feat/u3-agent-adapter` 分支上从 `c406e9f` 出发，建 SpringAiAgentAdapter + Advisor Chain + OutputSchemaValidator。读完这一份 + `docs/plans/agentflow/05-implementation-units.md` 的 U3 节 + `03-key-technical-decisions.md` 的 KTD-6/KTD-7，就能动手。最后更新：2026-07-03。

## 起点

- **分支**：`feat/u3-agent-adapter`（已推 origin，track origin/feat/u3-agent-adapter）
- **HEAD**：`c406e9f chore(u3): bump Spring Boot 3.4→4.1 + Spring AI 2.0 BOM + KTD-7 冒烟通过`
- **已就位**：parent pom 升到 Spring Boot 4.1.0 + spring-ai-bom 2.0.0 import；`agentflow-adapters/spring-ai` 模块已加 `spring-ai-starter-model-openai` 依赖；`SpringAiApiSmokeTest`（3 tests，KTD-7 冒烟绿）。
- **全仓基线**：`mvn -s settings.xml -B -ntp verify` 5 模块 SUCCESS（core 57 tests + adapter 冒烟 3），JaCoCo 80% 达标。
- **Maven**：本机装在 `~/apache-maven-3.9.16`（不在 PATH）。PowerShell 每会话先设：
  ```powershell
  $env:MAVEN_HOME="$env:USERPROFILE\apache-maven-3.9.16"; $env:PATH="$env:MAVEN_HOME\bin;$env:PATH"
  ```
  仓库 `settings.xml`（alimaven 镜像，gitignored）已在根目录。

## OQ-2 决议（重要，已偏离 plan 猜测）

plan 当初猜 Spring AI 2.0 要 Spring Boot 3.5——**不够**。Boot 3.5 仍带 Jackson 2.19（`com.fasterxml`），而 Spring AI 2.0.0 的 @Tool schema 路径用 **Jackson 3**（`tools.jackson.core`，需 `com.fasterxml.jackson.annotation.JsonSerializeAs`）。3.5 下冒烟：Advisor Chain 过，@Tool 注册 `NoClassDefFoundError: JsonSerializeAs`。

**正确版本：Spring Boot 4.1.0 GA**（自带 Jackson 3.1.4 + Spring Framework 7）。U3 起全仓升 Boot 4.1。不要再降到 3.x。

## KTD-7 冒烟已验证的 Spring AI 2.0 API 表面

`SpringAiApiSmokeTest` 已跑通，验证了以下 API（可直接用于适配器实现，不用再探）：

| 概念 | 类/方法 |
|:---|:---|
| ChatClient | `org.springframework.ai.chat.client.ChatClient`；`ChatClient.create(ChatModel)` 或 `ChatClient.builder(ChatModel)` |
| 请求构造 | `client.prompt(String).advisors(Advisor...).tools(Object...).toolContext(Map).call()` → `CallResponseSpec`；`.content()` → String |
| 自定义 Advisor | `org.springframework.ai.chat.client.advisor.api.BaseAdvisor`（implements CallAdvisor+StreamAdvisor）；实现 `before(ChatClientRequest, AdvisorChain)` + `after(ChatClientResponse, AdvisorChain)`；`getName()` + `getOrder()`（`org.springframework.core.Ordered`） |
| 请求/响应 | `ChatClientRequest` record：`.prompt()` + `.context()`（Map）；`ChatClientResponse` record：`.chatResponse()` + `.context()` |
| token | `chatResponse().getMetadata().getUsage()` → `Usage`：`getPromptTokens()`/`getCompletionTokens()`/`getTotalTokens()` |
| ChatModel stub | 实现 `org.springframework.ai.chat.model.ChatModel` 的 `ChatResponse call(Prompt)`；`new ChatResponse(List.of(new Generation(new AssistantMessage(content))), ChatResponseMetadata.builder().usage(new DefaultUsage(promptTok, completionTok)).build())` |
| @Tool 注册 | `@org.springframework.ai.tool.annotation.Tool(description=...)` 注解方法；`MethodToolCallbackProvider.builder().toolObjects(bean).build().getToolCallbacks()` → `ToolCallback[]`，`getToolDefinition().name()` = 方法名；或直接 `client.prompt(...).tools(bean)` |
| 内置 logger | `org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor`（可参考其实现） |

**2.0 相对 1.0 的改动点（避免踩错 API）**：
- Advisor 不再用 1.0 的 `BaseAdvisor.adviseRequest/adviseResponse`，而是 `before`/`after` 钩子（`BaseAdvisor` 接口）。
- `ChatClientRequest`/`ChatClientResponse` 是**顶级 record**（不是 `ChatClient` 内部类）。
- @Tool starter artifact 名是 `spring-ai-starter-model-openai`（不是旧的 `spring-ai-openai-spring-boot-starter`）。
- @Tool 方法 → ToolCallback 的工厂是 `MethodToolCallbackProvider`（不是旧的 `ToolCallbacks.from`）。

## 要建的文件（plan U3 Files + ce-code-review 留的 seam）

按 plan `05-implementation-units.md` U3 节，+ U2 审查（ce-code-review）留给 U3 的 seam：

### 1. 富化 AgentInput / AgentOutput（review #12/#13，U2 已留 seam）
- `agentflow-core/.../agent/AgentInput.java`：加 `List<String> tools` + `Map<String,Object> outputSchema` 字段（从 NodeDefinition 透传，U3 适配器需要）。BspEngine 构造 AgentInput 处同步补传（`node.tools()` / `node.outputSchema()`）。
- `agentflow-core/.../agent/AgentOutput.java`：加 `structuredOutput: Map<String,Object>`（plan 已规划，schema 校验后填充）+ `metadata: Map<String,Object>`（token 统计/trace id/延迟，**不挤占 channelWrites/structuredOutput**）。U3 的 TokenCountingAdvisor 写 metadata。
- record 新增字段会破坏现有构造点——同步更新 BspEngine + 所有测试夹具（量不大，grep `new AgentInput` / `new AgentOutput`）。

### 2. NodeRegistry（`agentflow-core/.../agent/NodeRegistry.java`）
- 按 name → AgentFunction 查找；Spring 容器扫描 `AgentFunction` Bean 注册。
- BspEngine 当前取 `Map<String,AgentFunction>`——NodeRegistry 可作为该 Map 的来源，或 BspEngine 改接受 `Function<String,AgentFunction>` resolver（NodeRegistry 实现它）。**优先用 resolver 形式**，最小改动 BspEngine。

### 3. ExecutionTrace（`agentflow-core/.../observability/ExecutionTrace.java`）
- 执行追踪树：根（workflow 级）+ 子（每节点执行：nodeId/duration/token/status/output 摘要）。
- U3 是首个消费者：LoggingAdvisor 写入、（U6）DiagnosisService 读、（U7）接 Micrometer。
- 不可变记录或可追加构建器；线程安全（多节点并行写子节点，barrier 后读）。

### 4. SpringAiAgentAdapter（`adapters/spring-ai/.../springai/SpringAiAgentAdapter.java`）— 核心
- 实现 `AgentFunction`。内部聚合 `ChatClient`（单例线程安全）。
- **execute(AgentInput)**：
  1. SpEL 解析 `input.promptTemplate()` 中的 `${...}` → 最终 prompt（见下 SpEL 安全）
  2. `chatClient.prompt(resolvedPrompt).advisors(tokenCountingAdvisor, loggingAdvisor).tools(toolBeans).call()`
  3. 从 `ChatResponse` 取 content + usage；构造 `AgentOutput(content, channelWrites, structuredOutput, metadata{tokens})`
  4. 异常映射：Spring AI `NonTransientAiException` → `TransientException`；`TransientAiException`/网络 → `TransientException`；其余 → `FatalException`（U4 ErrorClassifier 据此重试）
- **cancel(AgentInput) 覆盖（KTD-6 v4.3，必须）**：真中止在飞 LLM HTTP 调用。Spring AI 2.0 ChatClient.call() 同步阻塞——适配器需把 call 跑在可中断的载体上（Virtual Thread + Future），cancel 时 `future.cancel(true)` + 触发底层 RestClient abort。**这一步冒烟没覆盖（stub ChatModel 无 HTTP）**——U3 实现时要写一个用 mock RestClient/ChatModel 验证 cancel 被 abort 的测试。如果 Spring AI 2.0 没有干净的 HTTP abort 钩子，记进 commit message 并降级为 best-effort 中断 + warn。
- **可移植性（KTD-7）**：所有 Spring AI API 调用收敛在适配器内，经 `AgentFunction` SPI 对外暴露。2.0→2.1 迁移只碰适配器。

### 5. TokenCountingAdvisor（`adapters/spring-ai/.../springai/TokenCountingAdvisor.java`）
- 实现 `BaseAdvisor`；`after()` 从 `response.chatResponse().getMetadata().getUsage()` 取 prompt/completion tokens。
- 写入 `AgentOutput.metadata`（通过 ThreadLocal 或 context 传回适配器——VT 下 ThreadLocal 脆弱，优先用 `ChatClientResponse.context()` Map 传值）+ 接 Micrometer Counter（U7 完整接入，U3 先写 metadata）。

### 6. LoggingAdvisor（`adapters/spring-ai/.../springai/LoggingAdvisor.java`）
- 实现 `BaseAdvisor`；`before`/`after` 写 `ExecutionTrace` 节点（nodeId/duration/status）。
- **PromptRedactionFilter 注入其之前做脱敏**（U14），U3 留 seam：LoggingAdvisor 暴露一个可插拔的 `Function<String,String>` redactor（默认 identity）。

### 7. OutputSchemaValidator（`adapters/spring-ai/.../springai/OutputSchemaValidator.java`）
- 依赖 `com.networknt:json-schema-validator`（**需加到 adapter 模块 pom**，版本 Spring Boot 4.1 BOM 未管理，显式指定，如 `1.5.8` 或最新稳定）。
- 工作流：① 正则从 LLM 文本提取 ```` ```json ... ``` ```` 或纯 JSON；② json-schema-validator 校验 `outputSchema`；③ 失败构造带反馈的重试 prompt（附 schema + 错误）让模型重生；④ 最多重试 2 次，仍失败 `FatalException`。
- **retry 预算（plan v4.2）**：schema-retry 与 U4 RetryPolicy 共享 per-node LLM 调用总预算 max 3 次；schema-retry 嵌进 RetryPolicy 一次 attempt 内，封顶 3×(1+2)=9。U3 先实现 schema-retry 本身（≤2 次），U4 接入共享计数器。

### 8. 测试
- `SpringAiAgentAdapterTest`：mock ChatModel（沿用 smoke 的 stub 模式）+ SpEL 解析（`${context.financeAnalysis.riskScore}` 从嵌套 map 取值）+ SpEL 安全（`${T(java.lang.System).exit(0)}` 被禁）+ Transient vs Fatal 异常分类 + cancel 被调。
- `OutputSchemaValidatorTest`：校验成功 / 失败+重试成功 / 失败 2 次→FatalException 三路径。
- 保留 `SpringAiApiSmokeTest`（KTD-7 冒烟，作为 API 回归）。

## SpEL 安全（KTD-2）

- 用 `org.springframework.expression.spel.standard.SpelExpressionParser` + `SimpleEvaluationContext.forReadOnlyDataBinding().build()`（禁反射/类引用）。
- 定界符 `${...}`（plan 约定，与 demo 引用同路径，**不是** Spring 标准的 `#{}`）。
- 根对象：合并 `AgentInput.context()`（channel 值）+ `structuredOutput`（优先）+ `inputs`。SpEL 引用优先读 structuredOutput，降级 content。
- 安全测试：`${T(java.lang.System).exit(0)}` 必须抛异常（SimpleEvaluationContext 禁 `T()` 类引用）。

## 怎么构建 / 测试

```powershell
# 每会话先设 Maven PATH（见上）
mvn -s settings.xml -B -ntp -pl agentflow-adapters/spring-ai -am test     # 只跑 adapter + core 测试
mvn -s settings.xml -B -ntp verify                                          # 全仓 + JaCoCo 80% 门禁
```

- **JaCoCo 80%** 从 U1 起强制——adapter 模块新增代码覆盖率 < 80% → verify 失败。
- 新增依赖（json-schema-validator）后先 `mvn dependency:resolve` 确认能拉到。

## 执行顺序建议

1. 富化 AgentInput/AgentOutput（+ 同步更新 BspEngine 构造 + 测试夹具）→ verify 绿
2. NodeRegistry + ExecutionTrace（数据结构先行）→ verify 绿
3. SpringAiAgentAdapter 骨架（execute 用 stub ChatModel，无 cancel/schema）→ adapter 测试绿
4. TokenCountingAdvisor + LoggingAdvisor（接入 ChatClient Advisor Chain）→ 绿
5. OutputSchemaValidator + json-schema-validator 依赖 → 三路径测试绿
6. cancel() 覆盖 + abort 测试（最不确定的一步，单独 commit）
7. 全仓 verify + ce-code-review（U3 是大单元，建议建完跑一轮审查）→ 合 main

## 开放项 / 需实现时确认

- **cancel 真中止 HTTP**：Spring AI 2.0 ChatClient 的 HTTP abort 钩子待实现时确认（冒烟未覆盖）。若无可干净 abort 的 API，降级 best-effort + warn 日志，记 commit。
- **OutputSchemaValidator 的 json-schema-validator 版本**：确认与 Jackson 3 兼容的版本（networknt 1.5.x 可能仍依赖 Jackson 2——若冲突，改用 Spring AI 自带的 schema 工具或手写最小校验器）。
- **metadata 回传路径**：TokenCountingAdvisor → AgentOutput.metadata 用 `ChatClientResponse.context()` Map 还是别的？实现时定，避开 ThreadLocal（VT 脆弱）。

## 完成判定

- `mvn verify` 5 模块 SUCCESS，adapter JaCoCo ≥ 80%
- plan U3 全部 Test scenarios 覆盖（SpEL 解析/安全、NodeRegistry lookup、@Tool 注册、Transient vs Fatal、schema 校验三路径）
- CLAUDE.md "当前进度" 段更新 U3 = 已落地
- 合 `feat/u3-agent-adapter` → `main`，推 origin

← 返回 [CLAUDE.md](../../CLAUDE.md)
