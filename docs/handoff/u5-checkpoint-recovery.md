# U5 接手清单 — 两级 Checkpoint 持久化 + Recovery

> 给下一个 Claude Code 会话：在 `feat/u5-checkpoint` 分支上，代码已写完但**未跑 `mvn verify`**（当前环境无 Maven）。拿到有 Maven 的环境后先跑验证。最后更新：2026-07-21。

## 起点

- **分支**：`feat/u5-checkpoint`（本地，**未推远程**）
- **基于**：`origin/main`（`b4a163b`，U4 已合）
- **已就位**：12 个文件（8 生产 + 3 修改 + 2 测试），所有代码已写完、自审通过
- **基线**：U4 时为 `mvn verify` 5 模块 SUCCESS（136 tests，JaCoCo 80%），U5 在此基础上新增

## 文件清单

### 新增生产文件（8）

| 文件 | 说明 |
|:---|:---|
| `agentflow-core/.../checkpoint/NodeOutputStore.java` | 节点级 checkpoint record（映射 `workflow_node_outputs` 行） |
| `agentflow-core/.../checkpoint/NodeStatus.java` | 节点状态枚举：IN_PROGRESS / COMPLETED / FAILED |
| `agentflow-core/.../checkpoint/BarrierCheckpoint.java` | Barrier 级 checkpoint record（映射 `workflow_checkpoints` 行） |
| `agentflow-core/.../checkpoint/WorkflowStatus.java` | 工作流状态枚举：PENDING / RUNNING / SUCCESS / FAILED |
| `agentflow-core/.../checkpoint/ExecutionState.java` | Recovery 恢复结果 record（nextSuperStep + channelSnapshot + completedNodeIds） |
| `agentflow-core/.../checkpoint/InMemoryCheckpointManager.java` | 内存实现（ConcurrentHashMap 存储，开发测试用，重启丢失） |
| `agentflow-core/.../checkpoint/PostgresCheckpointManager.java` | PG 实现（JdbcTemplate + Flyway + Semaphore(20) + ON CONFLICT 幂等） |
| `agentflow-core/.../checkpoint/RecoveryProtocol.java` | 崩溃恢复协议（off-by-one 修复：查 nextSuperStep 非 nextSuperStep-1） |
| `agentflow-core/src/main/resources/db/migration/V1__checkpoint_schema.sql` | Flyway 迁移：3 表（workflow_executions / workflow_node_outputs / workflow_checkpoints）+ 2 索引 |

### 修改已有文件（3）

| 文件 | 变更 |
|:---|:---|
| `agentflow-core/pom.xml` | 添加 spring-boot-starter-jdbc / postgresql / flyway-core / flyway-database-postgresql / h2(test) |
| `agentflow-core/.../checkpoint/CheckpointManager.java` | 接口扩展：+ findLatestBarrier / findCompletedNodes / initWorkflow / updateStatus |
| `agentflow-core/.../checkpoint/NoopCheckpointManager.java` | 实现新增接口方法（空操作） |

### 测试文件（2）

| 文件 | 场景数 | 覆盖 |
|:---|:---:|:---|
| `agentflow-core/.../checkpoint/CheckpointManagerTest.java` | 12 | 节点写入+查询 / 幂等 / barrier 多步 / 并发 50 VT / JSONB 往返 |
| `agentflow-core/.../checkpoint/RecoveryProtocolTest.java` | 8 | 中间崩溃恢复 / 首次执行 / 跳过 COMPLETED / off-by-one 修复 / workflow 隔离 |

## 核心设计决策（记此备查）

### 1. COMPLETED 同步写（v4.3）
节点执行完成 → 立即同步 INSERT（非异步队列），兑现 R3"节点完成立即保存"。barrier 合并前数据已落盘，无 pre-barrier flush 窗口。

### 2. Semaphore(20) 限流
`PostgresCheckpointManager` 内置 `Semaphore(20)`，每次 `saveNodeOutput`/`saveBarrier` 前 acquire、后 release。防 VT 并发 50+ 耗尽 HikariCP 连接池。

### 3. ON CONFLICT 幂等 upsert
```sql
ON CONFLICT (workflow_id, super_step, node_id)
DO UPDATE SET ... WHERE workflow_node_outputs.status <> 'COMPLETED'
```
COMPLETED 终态不可覆盖；FAILED/IN_PROGRESS 可升级为 COMPLETED。避免 retry 成功后被 DO NOTHING 丢弃。

### 4. Off-by-one 修复
Recovery 查询 `nextSuperStep`（崩溃层本身），而非 `nextSuperStep - 1`（已 barrier 的层）。后者导致崩溃层已完成节点被重复执行、LLM 重复计费。

### 5. Double protection
查询 COMPLETED 节点：`status = 'COMPLETED' AND output IS NOT NULL`。防止 IN_PROGRESS stray 记录或 output 缺失的 COMPLETED 被误用于恢复跳过。

### 6. ChannelValue → value 提取
`saveBarrier` 从 `WorkflowContext.values()`（`Map<String, ChannelValue>`）提取原始值（`ChannelValue.value()`）存入 JSONB——不存 ChannelValue 封装（含 version 字段），序列化更干净。

### 7. Flyway 迁移自动执行
`PostgresCheckpointManager` 构造时调用 `Flyway.migrate()`——幂等，仅执行待迁移的 SQL。迁移文件在 `classpath:db/migration/V1__checkpoint_schema.sql`（Flyway 默认路径）。

## 验证命令

```bash
# 前提：JDK 21 + Maven 3.9+（settings.xml 已配 alimaven 镜像）
cd AgentFlow
git checkout feat/u5-checkpoint

# 步骤 1：编译（确认依赖可解析、无编译错误）
mvn -s settings.xml -B -ntp compile

# 步骤 2：仅跑 core 模块测试（含 U5 测试，不需要 PG）
mvn -s settings.xml -B -ntp -pl agentflow-core test

# 步骤 3：完整验证（含 JaCoCo 80% 门禁）
mvn -s settings.xml -B -ntp verify

# 步骤 4：集成测试（需 PG，用 docker-compose 起）
docker compose -f docker-compose.test.yml up -d
mvn -s settings.xml -B -ntp verify
```

## 验证通过后的合并步骤

```bash
# 全绿 → commit + push + 合 main
git add -A
git commit -m "feat(checkpoint): U5 两级 Checkpoint 持久化 + Recovery Protocol"
git push -u origin feat/u5-checkpoint
# 然后开 PR 合 main（或本地 fast-forward merge）
```

## 开放项 / 已知风险

1. **`?::jsonb` cast 语法**：PostgresCheckpointManager 用 `VALUES (?, ?, ?, ?::jsonb, ...)` 传 JSON 字符串。PG JDBC 驱动应将 String 参数按 text 发送、由 PostgreSQL `::jsonb` 转换。若编译/测试时 `?::jsonb` 报 `syntax error`，改用 `PGobject.setType("jsonb")` + `PreparedStatement.setObject`（需 import `org.postgresql.util.PGobject`，依赖已就位）。

2. **flyway-database-postgresql 版本**：Spring Boot 4.1 parent 管理 flyway-core 版本但对 flyway-database-postgresql 的兼容性取决于实际 BOM。若编译报 `flyway-database-postgresql` 版本不兼容，在 parent pom 的 `<dependencyManagement>` 中显式声明版本。

3. **InMemoryCheckpointManager 不限制机重启**：所有数据在 ConcurrentHashMap 中，进程重启全丢失——这是设计，不是 bug。开发测试场景用 InMemory，生产用 PG。

4. **WorkflowContext 更新不在此次**：BspEngine 目前不调 `initWorkflow`/`updateStatus`——生命周期方法由调用方（U13 starter / U14 controller）使用。引擎内部执行流不变。
