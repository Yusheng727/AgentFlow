# Plan · C2：OutputSchemaValidator 下沉 core + 接入 LangChain4j schema 校验

> 执行人/日期：2026-08-11 · 前置障碍：ce-code-review 残留项 **C2**（`docs/residual-review-findings/langchain4j-adapter-review.md`）。
> 一句话目标：把框架无关的 `OutputSchemaValidator` 从 spring-ai 模块下沉 core，并让 `LangChain4jAgentAdapter` 接上——补上 KTD-7 "相同 DSL、相同结果"的可移植性对价（当前 LC4j 的 `structuredOutput` 恒空 + warn）。

## 背景（为什么做）

`LangChain4jAgentAdapter` 目前对 `output_schema` 只 `log.warn` 声明"不支持"，`structuredOutput` 恒 `Map.of()`——与 `SpringAiAgentAdapter`（支持 schema 校验 + 带反馈重试）**静默行为分歧**，违反 KTD-7 可移植性承诺。根因是 `OutputSchemaValidator` 在 spring-ai 模块、LC4j 模块零 Spring AI 依赖取不到。它本身是**框架无关**的（只依赖 networknt json-schema + Jackson 3 + core `FatalException`），下沉 core 后两适配器共用，单一真相源。

## 已核实的关键事实（决定可行性）

- `OutputSchemaValidator` 已是 `public class`，imports 仅：core `FatalException`、`com.networknt.schema.*`、`tools.jackson.databind.json.JsonMapper`（Jackson 3）+ java.util。**无 Spring AI**。
- networknt 版本：`json-schema-validator:3.0.1`（Spring AI 2.0 传递锁定，Jackson 3 原生）。
- core 编译类路径**已含** `tools.jackson.core:jackson-databind:3.1.4`（Jackson 3）→ 下沉只需给 core 加 networknt 依赖，版本对齐即可。
- `validateWithRetry(initialPrompt, schema, llmCaller: Function<String,String>) throws FatalException`——llmCaller 是 `Function`，**不能抛检查型异常**。而 LC4j 的 `chatWithTools` 因 ADV-1 修复抛**检查型** `FatalException`（工具链截断）→ schema 回调需包装 + 调用侧解包。

## Files

**Create**
- `agentflow-core/src/main/java/com/agentflow/prompt/OutputSchemaValidator.java`（从 spring-ai 移入，包改 `com.agentflow.prompt`，javadoc 注明 v1.1 下沉 core）
- `agentflow-core/src/test/java/com/agentflow/prompt/OutputSchemaValidatorTest.java`（从 spring-ai 测试移入）

**Modify**
- `pom.xml`（root）：`dependencyManagement` 加 `com.networknt:json-schema-validator:3.0.1`
- `agentflow-core/pom.xml`：加 `com.networknt:json-schema-validator`（版本走 root 管）
- `agentflow-adapters/spring-ai/.../SpringAiAgentAdapter.java`：import 改 `com.agentflow.prompt.OutputSchemaValidator`；删本地私有 `flattenChannels` 无关（已删），这里是 import 切换
- `agentflow-adapters/langchain4j/.../LangChain4jAgentAdapter.java`：加可空 `schemaValidator` 字段 + 7-arg 构造（旧构造委托 null）；execute 走 schema 校验路径；catch(RuntimeException) 加 FatalException 解包

**Delete**
- `agentflow-adapters/spring-ai/.../OutputSchemaValidator.java` + `OutputSchemaValidatorTest.java`

## Approach（按序，TDD 优先）

1. **依赖**：root `dependencyManagement` + core pom 加 networknt 3.0.1（对齐 Spring AI 传递版本）。verify core 可编译 Jackson 3 `JsonMapper`。
2. **移类到 core**：`OutputSchemaValidator` → `com.agentflow.prompt`（public；同 `SpelPromptResolver` 归置框架无关的 LLM I/O 工具；可选改 `final`）。测试随移 `com.agentflow.prompt.OutputSchemaValidatorTest`。
3. **Spring 适配器切换**：import 改 core，删 springai 类。跑 spring-ai 64 测试确认**零回归**。
4. **LC4j 接入**：
   - 构造器加可空 `OutputSchemaValidator schemaValidator`（7-arg 完整构造：`(chatModel, toolBeans, trace, redactor, metrics, model, schemaValidator)`；4-arg/6-arg 委托 null）。
   - `execute()`：`if (schemaValidator != null && !outputSchema().isEmpty())` → `validateWithRetry(resolvedPrompt, schema, p -> { LlmResult r = callForSchema(p, last); last.set(r); return r.content(); })`；`last` 是 `AtomicReference<LlmResult>`（捕获最后一次 usage，跨 schema 重试取末次，与 Spring 一致）。
   - **检查型 FatalException 穿过 Function 的处理**：schema 回调里 `chatWithTools` 的 `FatalException`（工具链截断）包成 `RuntimeException`（cause=FatalException）；`execute()` 的 `catch(RuntimeException)` 里先 `if (cause instanceof FatalException) throw (FatalException) cause;` 原样传播（避免被 `mapException` 再包成误导性的 "LLM 调用失败"）。
   - 移除旧的"不支持 output_schema" warn（改为真支持）。
5. **LC4j 测试**（`LangChain4jAgentAdapterTest` +3）：
   - schema 成功：`structuredOutput` 填充 + content 通过校验（stub ChatModel 返回合法 JSON）
   - schema 反馈重试：首次返回无效 JSON → 第二次合法 → 通过（验证带反馈重试）
   - schema 耗尽：始终非法 → `FatalException`（且 NodeTrace FAILED）
   - （可选）无 validator / 无 schema → 原行为不变
6. **core 单测**：`OutputSchemaValidatorTest` 移入后原样通过。

## Test scenarios / Verification

- **主验收**：全仓 `mvn -s settings.xml -B -ntp verify` → 10 模块绿 + JaCoCo 达标
- **Spring 零回归**：`agentflow-adapters/spring-ai` 64 tests 绿（schema 路径行为不变）
- **LC4j 新例**：schema 成功/重试/耗尽 3 个 + 原有 14 全绿
- **core**：`OutputSchemaValidatorTest` 通过（从 spring-ai 搬移）

## Scope bounds（明确不做）

- **B2** ErrorClassifier 不识别 `dev.langchain4j.*` 异常 —— 属另一项，不做。
- **C1** 真实路径 budget 挂钩 —— 单独项，不做。
- B1 / B3 / 工具循环重构 —— 不做。

## 验收后收尾

- commit（conventional, 中文描述）到 `feat/c2-lc4j-schema-validation` 分支 → 合本地 main → **推送**（用户上次明确要 push）。
- 同步文档：`03-review-findings.md`（C2 从 residual 标 ✅）、`docs/residual-review-findings/langchain4j-adapter-review.md`（C2 标已办）、`CLAUDE.md`（进度 + developer-notes 记一笔）。
