# AgentFlow 用户手册

> 面向开发者的 Multi-Agent 编排引擎使用指南。读完这份，你能用 YAML 声明多 Agent 工作流、本地零成本调试、生产部署崩溃可恢复。

---

## 1. AgentFlow 是什么

**Java 原生的 Multi-Agent 编排引擎**。你用 YAML 声明多个 AI Agent 的协作关系（谁先谁后、谁并行、数据怎么传），引擎负责调度执行——并行用 Virtual Threads、崩溃用两级 Checkpoint 恢复、恢复时不重复调用 LLM（不重复计费）。

### 解决什么问题

| 你现在的痛点 | AgentFlow 怎么解决 |
|:---|:---|
| 写 AI 应用要手写编排逻辑（线程池、Future、channel 合并） | YAML 声明，BSP 引擎自动分层 + 并行 + barrier 合并 |
| 崩溃后从头重跑，LLM 重复计费 | 两级 Checkpoint（节点级 + barrier 级），恢复时跳过已完成节点 |
| 生产恢复难，不知道哪些节点已完成 | Recovery Protocol 自动查崩溃层 COMPLETED 节点 + 重放未进 barrier 的输出 |
| 调试要花真金白银调 LLM | Mock 模式零成本，从 YAML mock_response 读预设响应 |

### 适用场景

- **供应商风险评估**：3 个专家 Agent（财务/合规/声誉）并行分析 → Supervisor 汇总评级
- **合同审核流水线**：4 步串行（合同解析 → 法律风险 → 合规建议 → 最终报告）
- **投资分析决策**：双层 fork-join（2 并行 → 1 串行 → 2 并行 → 1 汇总）

---

## 2. 三分钟跑通第一个工作流（Mock 模式，零 LLM 成本）

### 前置条件

- JDK 21+
- Maven 3.9+

### 步骤

```bash
git clone https://github.com/Yusheng727/AgentFlow.git
cd AgentFlow

# Mock 模式跑通供应商风险评估 Demo
mvn -pl demo-supplier-risk spring-boot:run \
  -Dspring-boot.run.arguments="--agentflow.mock.enabled=true"
```

控制台输出：

```
=== 供应商风险评估结果 ===
{"riskLevel":"LOW","confidence":0.85,"evidence":["财务健康","合规1次违规","声誉良好"],"recommendation":"可合作，建议持续监控环保合规"}
```

**发生了什么？** 3 个专家 Agent 并行分析 → Supervisor 汇总评级。BSP 引擎驱动，MockAgentFunction 从 YAML `mock_response` 读预设响应，零 LLM API 调用。

---

## 3. 核心概念

### 3.1 BSP 执行模型

Bulk Synchronous Parallel（批量同步并行）：

```
DAG 最长路径分层 → super-step 序列
  ↓
每个 super-step 内节点并行执行（Virtual Threads，互不可见）
  ↓
barrier 同步（等最慢的节点）→ Reducer 合并 channel → 写 barrier checkpoint
  ↓
下一个 super-step
```

**关键不变式**：同一 super-step 内的节点互不可见（只读快照），保证 BSP 语义 + 确定性。

### 3.2 YAML DSL

```yaml
agentflow:
  version: "1.0"          # 工作流版本（R14，缺失默认 "1.0"）

channels:                   # 数据管道声明
  financeAnalysis:
    reducer: overwrite       # 合并策略：overwrite/concat/max/custom
  complianceCheck:
    reducer: overwrite

nodes:                      # Agent 节点
  - id: financial-analysis  # 节点 id（= channel 名，便捷约定）
    agent: finance-agent    # agent name（NodeRegistry 查找）
    prompt_template: "分析供应商 ${supplier} 的财务风险"
    tools: [finance-db-query]
    timeout: 120s
    retry: { max_attempts: 3, initial_backoff: 1s }
    output_schema:          # LLM 输出 JSON Schema 校验
      type: object
      properties:
        riskLevel: { type: string, enum: [LOW, MEDIUM, HIGH] }
    mock_response: |        # Mock 模式预设响应（零 LLM 成本）
      财务风险：低
      资产负债率：35%

  - id: aggregate-rating
    agent: aggregate-agent
    prompt_template: |
      汇总三路评估：
      财务：${financial-analysis}
      合规：${compliance-check}
    mock_response: |
      {"riskLevel":"LOW","confidence":0.85}

edges:                      # 依赖关系
  - { from: financial-analysis, to: aggregate-rating }
  - { from: compliance-check, to: aggregate-rating }
```

### 3.3 两级 Checkpoint

| 级别 | 时机 | 作用 |
|:---|:---|:---|
| **节点级** | Agent 完成当下立即持久化 output | 防 LLM 重复计费（恢复时跳过已完成节点） |
| **barrier 级** | super-step 合并后持久化 channel 快照 | 防恢复时 channel 状态丢失 |

### 3.4 Recovery Protocol

崩溃恢复流程：
1. 查最新 barrier checkpoint → `nextSuperStep = latestBarrier + 1`
2. 查崩溃层（nextSuperStep）COMPLETED 节点 → 跳过 + 重放输出（replayOutputs）
3. 若工作流状态为 FAILED（timeout abort），忽略 stray COMPLETED 记录，崩溃层整体重跑

---

## 4. 三种运行模式

### 4.1 Mock 模式（零基础设施，本地调试）

```yaml
# application.yml
agentflow:
  mock:
    enabled: true
```

- 用 `MockAgentFunction`，从 YAML `mock_response` 读预设响应
- InMemory Checkpoint（重启丢失）
- 零 LLM 成本，验证拓扑 + 上下文传递 + Recovery

### 4.2 生产模式（PostgreSQL 持久化）

```yaml
agentflow:
  mock:
    enabled: false

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/agentflow
    username: agentflow
    password: ${POSTGRES_PASSWORD}
  ai:
    openai:
      api-key: ${SPRING_AI_OPENAI_API_KEY}   # 环境变量，禁止入 yml（R22）
```

- 用 `SpringAiAgentAdapter` + ChatClient 调真实 LLM
- PostgreSQL Checkpoint（崩溃可恢复）
- Flyway 自动迁移 schema

### 4.3 分布式模式（自定义 CheckpointManager）

实现 `CheckpointManager` 接口（如 Redis-backed），覆盖 starter 提供的 Bean。

---

## 5. 集成到你的项目

### 5.1 加 Starter 依赖

```xml
<dependency>
    <groupId>com.agentflow</groupId>
    <artifactId>agentflow-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 5.2 启用 AgentFlow

```java
@SpringBootApplication
@EnableAgentFlow
public class MyApp {
    public static void main(String[] args) {
        SpringApplication.run(MyApp.class, args);
    }
}
```

### 5.3 写工作流 YAML

放 `src/main/resources/workflows/my-workflow.yml`：

```yaml
agentflow:
  version: "1.0"

nodes:
  - id: greet
    agent: greeter
    prompt_template: "你好 ${name}"
    mock_response: "你好 世界"

edges: []
```

### 5.4 注册 Agent

```java
@Configuration
public class AgentConfig {
    @Bean
    AgentFunction greeter() {
        return input -> AgentOutput.of("hello from greeter");
    }

    @Bean
    NodeRegistry registry(Map<String, AgentFunction> agents) {
        return new NodeRegistry(agents);
    }
}
```

### 5.5 提交执行

**方式一：编程式**

```java
@Bean
String runWorkflow(WorkflowDSLParser parser, BspEngine engine,
                   NodeRegistry registry, CheckpointManager cp) throws Exception {
    WorkflowDefinition def = parser.parse(
        new ClassPathResource("workflows/my-workflow.yml").getInputStream());
    WorkflowContext result = engine.execute(
        def, registry, Map.of("name", "World"),
        cp, new ChannelReducer(), "wf-1");
    System.out.println(result.getValue("greet"));
    return "done";
}
```

**方式二：REST API**

```bash
curl -X POST http://localhost:8080/api/workflows \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{
    "workflowName": "greeting",
    "version": "1.0",
    "yamlContent": "agentflow: ...",
    "inputs": {"name": "World"}
  }'
# → 202 + {"workflowId": "wf-xxx", "status": "PENDING"}
```

---

## 6. REST API 参考

所有端点需 `X-API-Key` header（SHA-256 hash 校验）。

| 端点 | 方法 | 用途 |
|:---|:---|:---|
| `/api/workflows` | POST | 提交工作流（异步，立即返回 202 + workflowId） |
| `/api/workflows/{id}/status` | GET | 查询执行状态（仅创建者可查） |
| `/api/workflows/{id}/retry` | POST | 重试失败工作流（仅创建者可重试） |
| `/api/diagnosis` | POST | 分析执行轨迹，识别 5 类问题 |

### 鉴权示例

```bash
# 提交
curl -X POST http://localhost:8080/api/workflows \
  -H "X-API-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{"workflowName":"supplier-risk","version":"1.0","yamlContent":"...","inputs":{}}'

# 查状态
curl http://localhost:8080/api/workflows/wf-xxx/status \
  -H "X-API-Key: your-api-key"

# 重试
curl -X POST http://localhost:8080/api/workflows/wf-xxx/retry \
  -H "X-API-Key: your-api-key"
```

**IDOR 防护**：API Key A 创建的工作流，API Key B 无法查询/重试（返回 403）。

---

## 7. 调试与诊断

### 7.1 Dry-run（不调 LLM 验证拓扑）

```java
DryRunEngine dryRun = new DryRunEngine();
List<StepResult> results = dryRun.dryRun(def, Map.of());
// 返回每步预期 input/output schema，无需 mock_response
```

### 7.2 诊断 5 类问题

提交 `ExecutionTrace.Snapshot` 到 `/api/diagnosis`，自动识别：

| 问题类型 | 识别规则 | 修复建议 |
|:---|:---|:---|
| 连续超时 | FAILED + error 含 timeout | 延长 TimeoutPolicy 或检查 LLM 连通性 |
| Token 异常消耗 | token > 均值 ×3 + >100 阈值 | 检查 prompt 是否过长或模型参数 |
| SpEL 解析失败 | error 含 SpelEvaluation | 检查 `${channel}` 引用是否指向已存在 channel |
| Channel 缺失 | error 含 channel/null | 检查 edges 拓扑，确认上游产出 channel |
| 节点重复执行 | 同一 nodeId SUCCESS > 1 次 | 检查 RecoveryProtocol 是否正确跳过 completedNodeIds |

### 7.3 结构化日志

每条 Agent 执行输出一行 JSON：

```json
{"timestamp":"2026-07-31T10:00:00Z","workflowId":"wf-1","nodeId":"financial-analysis","agentName":"finance-agent","durationMs":12,"status":"SUCCESS","promptTokens":0,"completionTokens":0,"totalTokens":0,"outputSummary":"财务风险：低..."}
```

---

## 8. 生产部署

### 8.1 Docker Compose 一键启动

```bash
# Mock 模式（零基础设施）
docker compose --profile mock up

# 生产模式（PG + Redis + API）
docker compose --profile production up
```

### 8.2 生产部署清单

1. **配置 PostgreSQL**：`spring.datasource.url=jdbc:postgresql://...`
2. **配 LLM API Key**（环境变量，禁止入 yml）：`export SPRING_AI_OPENAI_API_KEY=sk-xxx`
3. **设 API Key**（16 字节以上安全随机字符串）：`export AGENTFLOW_API_KEY=...`
4. **关 mock 模式**：`agentflow.mock.enabled=false`
5. **启动**：`docker compose --profile production up`

### 8.3 崩溃恢复验证

```bash
# 启动工作流
curl -X POST .../api/workflows -d '...'

# 模拟崩溃（kill 进程）
# 重启后引擎自动从最新 barrier +1 恢复，已完成节点不重跑
```

---

## 9. 常见问题

### Q1: Mock 模式和真实模式怎么切换？

改 `agentflow.mock.enabled` 配置。Mock 用 MockAgentFunction（YAML mock_response），零 LLM 成本；真实用 SpringAiAgentAdapter（ChatClient 调 LLM），PostgreSQL checkpoint。

### Q2: 怎么加新的 Agent？

实现 `AgentFunction` 接口，注册为 Bean，加进 `NodeRegistry`：

```java
@Bean
AgentFunction myAgent() {
    return input -> AgentOutput.of("hello");
}

@Bean
NodeRegistry registry(Map<String, AgentFunction> agents) {
    return new NodeRegistry(agents);
}
```

### Q3: 支持 LLM 提供商？

Spring AI 2.0 统一接口，支持 OpenAI/Anthropic/Azure 等。换 starter 依赖即可：

```xml
<!-- OpenAI -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
<!-- Anthropic -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
```

### Q4: 支持哪些工作流拓扑？

静态 DAG（串行/并行/混合 fork-join）。v1 不支持条件分支（留给 v2）。最长路径分层算法把 DAG 自动转成 super-step 序列。

### Q5: 崩溃恢复怎么知道从哪接着跑？

引擎查 `latestBarrier.step = k` → `nextSuperStep = k+1`，查崩溃层 COMPLETED 节点跳过 + 重放输出。若工作流状态为 FAILED（timeout abort），崩溃层整体重跑避免读到 stray 输出。

### Q6: 启动报 "No qualifying bean of type DataSource"？

未配数据库但开了生产模式。切 mock 模式（`agentflow.mock.enabled=true`）或配 `spring.datasource.*`。

### Q7: 怎么 debug？

- Mock 模式零成本跑通全链路
- Dry-run 不调 LLM 看拓扑 I/O schema
- `ExecutionTrace` 记录每步 token/耗时/状态
- 诊断端点自动识别 5 类问题

---

## 10. 技术栈

- **Java 21**（Virtual Threads）
- **Spring Boot 4.1** + **Spring AI 2.0** GA
- **PostgreSQL** + **Flyway**（schema 迁移）
- **Jackson 3**（JSON/YAML）
- **Maven 多模块**（parent + core/adapters-spring-ai/api/starter/demo）

---

## 11. 下一步

- **可观测性**（U7）：Micrometer 指标 + Grafana Dashboard + LLM 成本核算
- **版本管理**（U8）：YAML 版本 bump 后旧实例按旧 DAG 执行
- **辅助 Demo**（U11/U12）：合同审核串行 + 投资分析双层 fork-join

详见 `docs/plans/agentflow/05-implementation-units.md`。
