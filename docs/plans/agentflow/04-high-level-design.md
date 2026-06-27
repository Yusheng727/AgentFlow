## High-Level Technical Design

### 整体架构

```mermaid
flowchart TB
    subgraph "用户接入层"
        API[REST API / YAML 提交]
        STARTER[Spring Boot Starter + @EnableAgentFlow]
    end

    subgraph "AgentFlow 编排引擎"
        DSL[YAML DSL 解析器 + 三层校验 + JSON Schema]
        DAG[DAG Builder + Super-step 分层算法]
        ENGINE[BSP 执行引擎]
        CTX[WorkflowContext + ChannelRegistry + Reducer]
        CP[两级 Checkpoint Manager: 节点级 + Super-step barrier]
        ADAPTER[Agent 适配器层]
    end

    subgraph "Agent 实现层"
        SPRING[Spring AI ChatClient Agent + Advisor Chain + @Tool]
    end

    subgraph "容错层"
        RETRY[RetryPolicy: 指数退避, 区分 transient/fatal]
        TIMEOUT[TimeoutPolicy: 节点级 + 工作流级]
        FALLBACK[ErrorHandler: 补偿逻辑]
    end

    subgraph "可观测层"
        TRACE[ExecutionTrace 执行追踪树]
        METRICS[Micrometer Metrics + 成本核算]
        GRAFANA[Grafana Dashboard]
        DIAG[Diagnosis End-point: 自动故障诊断]
    end

    subgraph "基础设施层"
        PG[(PostgreSQL / 节点输出 + Checkpoint + 任务状态)]
        REDIS[(Redis / 结果缓存 / 配置共享)]
    end

    API --> DSL
    STARTER --> ENGINE
    DSL --> DAG
    DAG --> ENGINE
    ENGINE <--> CTX
    ENGINE <--> CP
    ENGINE --> ADAPTER
    ADAPTER --> SPRING
    ENGINE --> RETRY
    ENGINE --> TIMEOUT
    ENGINE --> FALLBACK
    ENGINE --> TRACE
    ENGINE --> METRICS
    TRACE --> DIAG
    METRICS --> GRAFANA
    CP --> PG
    ENGINE --> REDIS
```

### BSP 执行模型与分层算法

```mermaid
flowchart LR
    A --> B
    A --> C
    B --> D
    C --> D
    D --> E
    D --> F
    E --> G
    F --> G
    
    subgraph "super-step 0"
        A
    end
    subgraph "super-step 1"
        B
        C
    end
    subgraph "super-step 2"
        D
    end
    subgraph "super-step 3"
        E
        F
    end
    subgraph "super-step 4"
        G
    end

    style super-step_0 fill:#e3f2fd
    style super-step_1 fill:#fff3e0
    style super-step_2 fill:#e8f5e9
    style super-step_3 fill:#f3e5f5
    style super-step_4 fill:#ffebee
```

**分层算法伪代码**：
```java
public List<SuperStep> computeSuperSteps(DAGraph<AgentNode> dag) {
    Map<String, Integer> levels = new HashMap<>();
    Queue<String> queue = new LinkedList<>();
    
    // Step 1: 所有入度为 0 的节点 → level 0
    for (AgentNode node : dag.getNodes()) {
        if (node.getInDegree() == 0) {
            levels.put(node.getId(), 0);
            queue.offer(node.getId());
        }
    }
    
    // Step 2: BFS 遍历，每个节点 level = max(前驱 level) + 1
    while (!queue.isEmpty()) {
        String current = queue.poll();
        for (AgentNode neighbor : dag.getSuccessors(current)) {
            int newLevel = levels.get(current) + 1;
            neighbor.inDegreeDec();  // 减少剩余未处理入度
            levels.merge(neighbor.getId(), newLevel, Math::max);
            if (neighbor.getInDegree() == 0) {
                queue.offer(neighbor.getId());
            }
        }
    }
    
    // Step 3: 按 level 分组
    return groupByLevel(levels);  // List<SuperStep>
}
```

### BSP 执行时序

```mermaid
sequenceDiagram
    participant U as 用户/API
    participant E as BSP Engine
    participant C1 as Node-level CP
    participant C2 as Barrier CP
    participant A1 as Agent 1(财务)
    participant A2 as Agent 2(合规)
    participant A3 as Agent 3(声誉)
    participant S as Supervisor Agent

    U->>E: 提交工作流
    Note over E: 初始化：nextSuperStep=0

    rect rgb(255, 250, 240)
        Note over E,A3: super-step 0: 并行分析
        par
            E->>A1: execute(context)
            A1-->>E: 财务结果
            E->>C1: 节点级 checkpoint(super_step=0, 财务节点)
            E->>A2: execute(context)
            A2-->>E: 合规结果
            E->>C1: 节点级 checkpoint(super_step=0, 合规节点)
            E->>A3: execute(context)
            A3-->>E: 声誉结果
            E->>C1: 节点级 checkpoint(super_step=0, 声誉节点)
        end

        Note over E: barrier: 合并三路结果到 context
        E->>C2: barrier checkpoint(super_step=0, 已完成)
    end

    rect rgb(240, 255, 240)
        Note over E,S: super-step 1: 汇总
        E->>S: execute(context)
        S-->>E: 综合评级
        E->>C1: 节点级 checkpoint(super_step=1, Supervisor)
        Note over E: barrier: 合并
        E->>C2: barrier checkpoint(super_step=1, 已完成)
    end

    E->>U: 返回结果

    Note over U,C2: 如果崩溃发生在 super-step 0 节点 C1 checkpoint 之后、barrier 之前 → 最新 barrier 无，nextSuperStep=0，查询 super_step=0 的 COMPLETED 节点 → 复用 C1 数据，仅重执行未完成节点
```

### AgentFunction 接口与 Advisor Chain 整合

```mermaid
flowchart LR
    INPUT[AgentInput] --> ENGINE[BSP Engine]
    ENGINE --> SPRING_ADAPTER[SpringAiAgentAdapter]
    
    subgraph "Spring AI 2.0"
        CHATCLIENT[ChatClient.prompt]
        CHATCLIENT --> ADVISOR1[TokenCountingAdvisor]
        ADVISOR1 --> ADVISOR2[LoggingAdvisor]
        ADVISOR2 --> ADVISOR3[ContentFilteringAdvisor 可选]
        ADVISOR3 --> MODEL[ChatModel.call]
    end
    
    MODEL --> OUTPUT[AgentOutput]
    
    subgraph "工具注册 via @Tool"
        TOOL1[财务数据库查询]
        TOOL2[合规 API 查询]
    end

    style SPRING_ADAPTER fill:#e3f2fd
    style ADVISOR1 fill:#fff3e0
```

### 两级 Checkpoint 数据模型

```mermaid
erDiagram
    WORKFLOW_EXECUTIONS {
        string workflow_id PK
        string workflow_name
        string version
        string status "PENDING/RUNNING/SUCCESS/FAILED"
        jsonb input_params
        jsonb result
        text error_message
        datetime started_at
        datetime completed_at
        text locked_by "实例标识"
        text created_by "caller API Key hash (R21 ownership)"
    }
    
    WORKFLOW_NODE_OUTPUTS {
        string workflow_id
        int super_step
        string node_id
        string status "COMPLETED/FAILED/IN_PROGRESS"
        jsonb output
        bigint tokens_consumed
        datetime completed_at
    }
    
    WORKFLOW_CHECKPOINTS {
        string workflow_id
        int super_step PK "composite"
        jsonb channel_values "channel -> value"
        jsonb channel_versions "channel -> version"
        jsonb execution_context
        datetime created_at
        string workflow_version "R14 版本隔离"
    }
    
    WORKFLOW_DEFINITIONS {
        string workflow_name PK "composite"
        string version PK "composite"
        jsonb definition "parsed DAG/YAML (v4.3)"
        datetime created_at
    }
    
    WORKFLOW_EXECUTIONS ||--o{ WORKFLOW_CHECKPOINTS : "per super-step"
    WORKFLOW_EXECUTIONS ||--o{ WORKFLOW_NODE_OUTPUTS : "per node"
    WORKFLOW_EXECUTIONS ||--|| WORKFLOW_DEFINITIONS : "name + version"
```

### Workflow 生命周期状态机

```mermaid
stateDiagram-v2
    [*] --> PENDING: POST /workflows
    PENDING --> RUNNING: 引擎开始执行
    RUNNING --> SUCCESS: 所有节点完成
    RUNNING --> FAILED: 不可恢复错误
    RUNNING --> WAITING_APPROVAL: 遇到人工审批节点(v2)
    WAITING_APPROVAL --> RUNNING: 审批通过
    RUNNING --> CANCELLED: 用户取消
    FAILED --> RUNNING: POST retry
    SUCCESS --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
```

← 返回 [`00-overview.md`](./00-overview.md)
