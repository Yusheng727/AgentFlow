# AgentFlow — Java 原生轻量级 Multi-Agent 编排引擎

YAML DSL 声明工作流，BSP（Bulk Synchronous Parallel）执行模型驱动，多 Agent 微服务式协作。
**零基础设施 Mock 模式，5 分钟跑通第一个工作流。**

## 架构

```
  POST /api/workflows  ──→  WorkflowController  ──→  BspEngine
       │                         │                      │
       │  YAML DSL                │  Auth (SHA-256)      │  Plan → Execute → Barrier
       ▼                         ▼                      ▼
  WorkflowDSLParser        ApiKeyAuthFilter      Virtual Threads
       │                                               │
       │  Parse + Validate                              │  Super-step 0
       ▼                                               │  ┌───┐ ┌───┐ ┌───┐
  WorkflowDefinition                                  │  │ A │ │ B │ │ C │ (parallel)
  (DAG + Channels)                                    │  └───┘ └───┘ └───┘
       │                                               │       barrier
       ▼                                               │  Super-step 1
  DAGLayerer                                           │  ┌───────────┐
  (最长路径分层 → super-steps)                            │  │  Supervisor │ (aggregate)
       │                                               │  └───────────┘
       ▼                                               │
  CheckpointManager           RecoveryProtocol         ▼
  (两级: 节点级 + barrier 级)   (崩溃恢复, off-by-one 修复)  WorkflowContext
                                                      (channel 快照)
```

## 快速开始（5 分钟，mock 模式，零 LLM 成本）

```bash
# 前置条件：JDK 21 + Maven 3.9+
git clone https://github.com/Yusheng727/AgentFlow.git
cd AgentFlow

# Mock 模式：一键跑通供应商风险评估 Demo
mvn -pl demo-supplier-risk spring-boot:run \
  -Dspring-boot.run.arguments="--agentflow.mock.enabled=true"
```

输出：

```
=== 供应商风险评估结果 ===
{"riskLevel":"LOW","confidence":0.85,"evidence":["财务健康","合规1次违规","声誉良好"],"recommendation":"可合作，建议持续监控环保合规"}
```

**发生了什么？** 3 个专家 Agent（财务/合规/声誉）并行分析 → Supervisor 汇总评级。BSP 引擎驱动，MockAgentFunction 从 YAML `mock_response` 读预设响应，零 LLM API 调用。

## Tutorial：三步接入

### 1. 加 Starter 依赖

```xml
<dependency>
    <groupId>com.agentflow</groupId>
    <artifactId>agentflow-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. 写工作流 YAML

`src/main/resources/workflows/my-first-workflow.yml`：

```yaml
agentflow:
  version: "1.0"

nodes:
  - id: greet
    agent: greeter
    prompt_template: "你好 ${name}"
    mock_response: "你好 世界"
```

### 3. 跑起来

```java
@SpringBootApplication
@EnableAgentFlow
public class MyApp {
    public static void main(String[] args) {
        SpringApplication.run(MyApp.class, args);
    }

    @Bean
    String run(WorkflowDSLParser parser, BspEngine engine,
               @Qualifier("mockAgentResolver") Function<String, AgentFunction> resolver,
               CheckpointManager cp) throws Exception {
        var def = parser.parse(new ClassPathResource("workflows/my-first-workflow.yml").getInputStream());
        var result = engine.execute(def, Map.of("greeter", resolver.apply("greeter")),
                Map.of("name", "World"), cp, new ChannelReducer(), "wf-1");
        System.out.println(result.getValue("greet"));
        return "done";
    }
}
```

## 接入模式

| 模式 | 配置 | Checkpoint | 适用 |
|:---|:---|:---|:---|
| **零基础设施（mock）** | `agentflow.mock.enabled=true` | InMemory（进程内） | 本地调试、开发验证 |
| **生产部署** | `agentflow.mock.enabled=false` + PG DataSource | PostgreSQL（持久化） | 生产环境 |
| **分布式** | 自选 CheckpointManager 实现 | 自定义（Redis/etcd 等） | 多实例部署 |

## 核心特性

- **YAML DSL**：声明式工作流定义，SNAKE_CASE 命名，Jackson 解析，三层校验
- **BSP 执行**：Virtual Threads 并行 + CompletableFuture.allOf barrier + 只读快照 + Reducer 确定性合并
- **两级 Checkpoint**：节点级（防 LLM 重复计费）+ barrier 级（崩溃恢复），PostgreSQL / InMemory 双实现
- **Recovery Protocol**：off-by-one 修复（查崩溃层本身），replayOutputs 重放未进 barrier 的输出，stray COMPLETED 防护
- **Mock LLM**：零成本本地调试，`${channel}` 占位符验证上下文传递
- **容错**：Timeout → ErrorClassifier（Transient/Fatal）→ Retry（指数退避 1s→2s→4s）→ ErrorHandler 三层链路
- **安全**：ApiKeyAuthFilter（SHA-256）+ IDOR 防护 + Tool 授权 + 凭证管理 + 敏感数据脱敏 + **列级静态加密**（`AgentFlow` 存库前 AES-256-GCM 加密 checkpoint 敏感列，R22）
- **Human-in-the-Loop 审批**：节点抛 `ApprovalRequiredException` 请求人工决策 → 引擎暂停至 `AWAITING_APPROVAL` + 审批单（上下文快照落库）→ REST 批准/拒绝后续跑（`ApprovalController`）
- **RAG Agent 扩展点（KTD-6）**：`demo-rag` 展示 `agent: rag` 节点「检索→增强→委托」，引擎层零改动（`RagEngineZeroChangeTest` 证明扩展点成立）

## 生产部署清单

1. **配置 DataSource**（PostgreSQL）：`spring.datasource.url=jdbc:postgresql://...`
2. **配 LLM API Key**（环境变量，禁止入 yml）：`export SPRING_AI_OPENAI_API_KEY=sk-xxx`
3. **设 API Key**（16 字节以上安全随机字符串）：`export AGENTFLOW_API_KEY=...`
4. **关 mock 模式**：`agentflow.mock.enabled=false`
5. **启动**：`docker compose --profile production up`

## FAQ

**Q: mock 模式和真实模式的区别？**

Mock 模式用 `MockAgentFunction`（从 YAML `mock_response` 读），零 LLM 成本，InMemory checkpoint（重启丢失）。真实模式用 `SpringAiAgentAdapter`（ChatClient 调 LLM），PostgreSQL checkpoint（崩溃可恢复）。

**Q: 怎么加新的 Agent？**

在 `NodeRegistry` 上注册 `AgentFunction` Bean：

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

**Q: 为什么从零复现（不用 LangGraph4j/Spring AI Alibaba）？**

AgentFlow 定位是简历项目——展示后端工程深度（BSP 引擎从零实现、两级 checkpoint、多 agent code review），不填补生态空白。见我 [developer-notes](./docs/developer-notes/01-implementation-rationale.md)。

**Q: 怎么 debug？**

- Mock 模式零成本跑通全链路
- 加 `spring.ai.openai.api-key` 切换到真实 Agent 调 LLM
- `ExecutionTrace` 记录每步 token/耗时/状态

## 仓库结构

```
AgentFlow/
├── agentflow-core/          # BSP 引擎 / DSL / Checkpoint / 容错 / 安全
├── agentflow-adapters/spring-ai/  # Spring AI 适配器 / Mock LLM
├── agentflow-api/           # REST 端点 / 鉴权
├── agentflow-starter/       # @EnableAgentFlow / AutoConfiguration
├── demo-supplier-risk/      # 供应商风险评估 Demo（3 并行 → 汇总）
├── demo-rag/                # RAG 检索增强 Demo（KTD-6 引擎零改动证明）
├── docker-compose.yml       # mock / production profiles
└── docs/
    ├── plans/agentflow/     # 计划文档（8 分片）
    ├── developer-notes/     # 开发笔记（面试弹药 + 实现决策 + bug 记录）
    └── TROUBLESHOOTING.md
```

## 技术栈

Java 21（Virtual Threads）/ Spring Boot 4.1 / Spring AI 2.0 / PostgreSQL / Flyway / Jackson 3 / Maven 多模块

## 构建

```bash
mvn verify                          # 全量测试 + JaCoCo 80% 门禁
mvn -pl agentflow-core test         # 只跑 core 测试
```
