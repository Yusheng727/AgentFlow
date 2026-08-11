# Plan · B2：LC4j 异常分类——适配器注册分类器（顺带解 M2）

> 执行人/日期：2026-08-11 · 前置障碍：ce-code-review 残留项 **B2**（+同源 M2），见 `docs/residual-review-findings/langchain4j-adapter-review.md`。
> 一句话目标：让 `ErrorClassifier` 正确识别 LangChain4j 的 transient 异常（网络/限流/超时可重试），且**把框架知识从 core 移回适配器**——一并解决 B2（LC4j 误判 Fatal 不重试）和 M2（core 含 `org.springframework.web.client.` 框架前缀污染）。

## 背景（为什么做）

`ErrorClassifier.defaultClassifier()`（core）只判 `java.net.*` / `org.springframework.web.client.*`。LangChain4j 的真实网络/限流/超时异常是 `dev.langchain4j.exception.*` 类型——不会被识别 → `LangChain4jAgentAdapter.mapException` 把它们误判 **Fatal → 不重试**，真实 LC4j 网络抖动直接工作流失败。同时 core 里的 `org.springframework.web.client.` 前缀是 **Spring 独有知识泄漏进框架无关的 core 层**（对 LC4j 路径是死代码）——M2 与 B2 同源，一并处理。

## 已核实的关键事实（决定方案）

- **LC4j 1.0.0 有干净的异常分类**（`dev.langchain4j.exception`）：
  - `RetriableException extends LangChain4jException` —— **可重试标记**；`RateLimitException`/`InternalServerException`/`TimeoutException`(LC4j 自己的) **都继承它**
  - `NonRetriableException extends LangChain4jException` —— **不可重试标记**；`AuthenticationException`/`InvalidRequestException`/`ModelNotFoundException`/`UnresolvedModelServerException` 继承它
  - `UnsupportedFeatureException`/`ModelDisabledException`/裸 `LangChain4jException` —— 无标记，保守 fatal
  - ⇒ **一个 `instanceof RetriableException` 就覆盖全部可重试子类**，且跟随框架标记演进（比手列子类稳）
- **core 是框架无关的**（KTD-7 精神 + M2 警告）：不能为 instanceof langchain4j 类型而在 core 引入 langchain4j 依赖。
- **适配器先 map 再抛**：`RetryPolicy` 的默认分类器只对已映射的 `TransientException`/`FatalException` 生效 → 从 core 移除 spring 前缀**不影响引擎重试路径**（spring 前缀在 core 原本就是死代码）。
- `ErrorClassifierTest` 当前不测 spring 前缀，移除无破坏。

## 方案：适配器注册框架分类器（composed 组合）

core 保持框架无关；各框架适配器注册自己的 transient 分类器。这是 B2 + M2 的**同一解**。

**Files**
- `agentflow-core/.../fault/ErrorClassifier.java`：移除 `org.springframework.web.client.` 前缀；新增 `static ErrorClassifier composed(ErrorClassifier... frameworks)`（任一框架判 transient OR 基础规则判 transient → transient）；javadoc 更新
- `agentflow-core/src/test/java/.../fault/ErrorClassifierTest.java`：补 composed 组合测试（框架分类器生效 / 基础规则仍生效 / 无 spring 前缀）
- `agentflow-adapters/spring-ai/.../SpringAiAgentAdapter.java`：`mapException` 改用 `composed(springPrefixClassifier)`（保留 `org.springframework.web.client.` 行为，但知识移到 Spring 适配器）
- `agentflow-adapters/langchain4j/.../LangChain4jAgentAdapter.java`：`mapException` 改用 `composed(lc4jClassifier)`（`cause instanceof dev.langchain4j.exception.RetriableException`）
- `agentflow-adapters/langchain4j/.../LangChain4jAgentAdapterTest.java`：补 LC4j 异常映射测试

## Approach（按序）

1. **core ErrorClassifier**：加 `composed(...)`；从 `defaultClassifier()` 移除 spring 前缀。`ErrorClassifierTest` 补：`composed(fw)` 中 fw 判 transient → true；fw 判 false 但基础规则（IOException）判 transient → true；无框架分类器时 spring 前缀不再判 transient。
2. **SpringAiAgentAdapter**：`mapException` → `composed(cause -> cause != null && cn.startsWith("org.springframework.web.client."))`。行为零回归（spring 前缀仍被识别）。
3. **LangChain4jAgentAdapter**：`mapException` → `composed(cause -> cause instanceof dev.langchain4j.exception.RetriableException)`。补测试：
   - ChatModel 抛 `RateLimitException`（Retriable 子类）→ `TransientException`（可重试）
   - ChatModel 抛 `AuthenticationException`（NonRetriable 子类）→ `FatalException`（不重试）
   - （既有 IO→Transient、IllegalState→Fatal 测试保持通过）
4. **验收**：全仓 `mvn -s settings.xml -B -ntp verify` 10 模块绿 + JaCoCo。

## Test scenarios / Verification

- **ErrorClassifierTest**：composed 组合 3 例 + 既有 8 例通过
- **LangChain4jAgentAdapterTest**：新增 LC4j transient/fatal 映射 2 例 + 既有 17 例通过
- **SpringAiAgentAdapterTest**：既有 15 例通过（mapException 行为零回归）
- **主验收**：全仓 verify 绿 + JaCoCo

## Scope bounds（明确不做）

- **C1** 真实路径 budget 挂钩 —— 单独项。
- B1（自定义 ToolExecutor）/ B3（继承 @Tool）—— 不做。
- 不引入 langchain4j 依赖到 core（保持框架无关，这是本方案的出发点）。

## 验收后收尾

- commit（conventional, 中文）到 `feat/b2-lc4j-exception-classification` → 合本地 main → **推送**（沿用惯例）。
- 同步文档：`docs/residual-review-findings/langchain4j-adapter-review.md`（B2/M2 标 ✅）、`03-review-findings.md`（B2/M2 已解决）、`CLAUDE.md`（进度）、`developer-notes/02`（设计决策）、`04-glossary`（可选）。
