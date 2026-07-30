# AgentFlow Troubleshooting — Top 10 FAQ

## 1. 启动报 `No qualifying bean of type 'javax.sql.DataSource'`

**现象**：`Error creating bean 'postgresCheckpointManager': No qualifying bean of type 'DataSource'`

**原因**：未配置数据库连接，但 `agentflow.mock.enabled=false`（或不设，默认 production 模式）。

**解决**：
```yaml
# 方案 A：切 mock 模式（零基础设施）
agentflow:
  mock:
    enabled: true

# 方案 B：配 DataSource
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/agentflow
    username: agentflow
    password: agentflow
```

---

## 2. mock 模式跑起来但汇总节点输出 `${financial-analysis}` 而不是替换后的值

**现象**：汇总节点的输出包含原始占位符 `${xxx}` 而不是三路评估内容。

**原因**：YAML 的 channel 名和 nodeId 不匹配。引擎便捷约定写入 `channel = nodeId`（如 `financial-analysis`），而不是 YAML `channels:` 段声明的名。

**解决**：统一 channel 名为 nodeId（用 `-` 连接而非 camelCase）：
```yaml
channels:
  financial-analysis: { reducer: overwrite }  # ← 与 node id 一致
nodes:
  - id: financial-analysis  # ← 同名
```

---

## 3. `MissingMockResponseException: Mock 模式下节点 X 缺少 mock_response 配置`

**现象**：mock 模式启动时抛 `MissingMockResponseException`。

**原因**：启用了 `agentflow.mock.enabled=true`，但 YAML 中某节点未配置 `mock_response` 字段。

**解决**：给每个节点加 `mock_response`：
```yaml
nodes:
  - id: my-node
    agent: my-agent
    mock_response: "这里是预设的 mock 响应"
```

---

## 4. `mvn verify` 失败：`Coverage checks have not been met`

**现象**：JaCoCo 覆盖率 < 80%。

**原因**：新增代码缺少测试覆盖，或 demo 模块的 Agent 类在 mock 模式下未被调用。

**解决**：
- `agentflow-core`/`adapters`/`api`/`starter`：必须 ≥ 80%，补测试
- `demo-supplier-risk`：已跳过 JaCoCo（验证场景，非核心引擎），见其 pom.xml `<jacoco.skip>true</jacoco.skip>`

---

## 5. 测试跑 `AgentInput.of(...)` 编译错误：构造器参数不匹配

**现象**：老代码调 `new AgentInput(...)` 7 参数 → 编译报「需要 8 参数」。

**原因**：U9 给 `AgentInput` record 加了 `mockResponse` 字段（第 8 个字段）。

**解决**：所有调用点补 `, null`：
```java
// 旧（7 参）
new AgentInput(id, name, prompt, ctx, inputs, tools, schema)
// 新（8 参）
new AgentInput(id, name, prompt, ctx, inputs, tools, schema, null)
```

---

## 6. Docker compose 启动后 demo 没输出

**现象**：`docker compose --profile mock up` 启动成功但看不到工作流结果。

**原因**：Application 的 `runSupplierRiskWorkflow()` Bean 在 `demo-supplier-risk/application.yml` 里未设 `agentflow.mock.enabled=true`。

**解决**：确认 `demo-supplier-risk/src/main/resources/application.yml` 有：
```yaml
agentflow:
  mock:
    enabled: true
```

---

## 7. `RecoveryProtocol` 恢复后下游节点读到 null channel

**现象**：崩溃恢复后，下游节点读到 null 而非上游输出。

**原因**：U5 P0 修复 ADV-1——崩溃层 COMPLETED 节点的 channelWrites 未进上一 barrier，`channelSnapshot` 缺这些输出。

**说明**：此问题已在 U5 `replayOutputs` 机制中修复——恢复时把崩溃层 COMPLETED 节点的输出重放进 context。详见 `docs/developer-notes/02-bugs-and-fixes.md` Bug-4。

---

## 8. `Matcher.appendReplacement` 抛出 `IllegalArgumentException: No group with name`

**现象**：`MockAgentFunction` 在替换 `${nonexistent}` 时抛异常。

**原因**：`appendReplacement` 把替换串中的 `$` 当 group 引用——`${nonexistent}` 的 `{nonexistent}` 被解析为 named group。

**解决**：此问题已在 U9 修复——未找到的占位符用 `Matcher.quoteReplacement(m.group())` 转义后再 `appendReplacement`。

---

## 9. 启动时 `CredentialManager` 抛异常

**现象**：`CredentialManager detected hardcoded API key in application.yml`。

**原因**：R22 安全要求——LLM API Key 必须从环境变量读取，禁止写入 application.yml。

**解决**：
```bash
export SPRING_AI_OPENAI_API_KEY=sk-xxx
```
不要在 yml 里写 `spring.ai.openai.api-key`（启动检测到会 fail-fast）。

---

## 10. `PostgresCheckpointManager` 启动慢 / Flyway 迁移失败

**现象**：`FlywayException: Unable to connect to database`。

**原因**：`PostgresCheckpointManager` 构造函数同步执行 `Flyway.migrate()`，DB 不可达阻塞 Bean 创建。

**解决**：
- 确认 PG 已启动：`docker compose --profile production up -d postgres`
- 确认 `spring.datasource.url/user/password` 配置正确
- V1/V2 迁移含幂等 `IF NOT EXISTS`，可重复执行
