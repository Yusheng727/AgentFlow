# Code Review 发现与修复

> 记录 ce-code-review 多 agent 审查的高价值发现 + 修复思路。
> **面试用途**：「怎么保证代码质量」「有没有做 code review」「工程 rigor」类问题的核心证据。
> ce-code-review 是 compound-engineering 插件的审查 skill：派 10 个 persona reviewer 并行审，交叉验证后 merge/dedup。

---

## U5 Review 概览

- **审查方式**：ce-code-review 多 agent 流程，10 个 persona reviewer 并行
- **diff 规模**：17 文件，~1580 行
- **reviewer 清单**：correctness / testing / maintainability / project-standards / performance / api-contract / data-migration / reliability / adversarial / agent-native / learnings-researcher
- **关键机制**：cross-reviewer agreement——多个 reviewer 独立报告同一问题，confidence 提权。U5 最严重的两个 P0 被 **4 个 reviewer 独立确认**（adversarial + correctness + reliability + testing），可信度极高。

**面试讲法**：「我不只写测试，还跑了多 agent code review——10 个不同视角的 reviewer（correctness、security、adversarial 等）并行审我的 diff，交叉验证发现的问题。最严重的两个 P0 是 adversarial reviewer 用混沌工程思路构造的崩溃时序攻击场景，另外 3 个 reviewer 独立佐证了同一根因，我才确信这是真问题不是误报。」

---

## 高价值发现（按严重度）

### P0-1: 崩溃层 COMPLETED 节点的 channel 输出丢失（ADV-1）

详见 [02-bugs-and-fixes.md Bug-4](./02-bugs-and-fixes.md#bug-4p0-崩溃层-completed-节点的-channel-输出丢失--adv-1)。

**为什么这个发现有价值**：这不是「代码写错了」，而是「恢复协议设计层面有缺陷」。plan 的 off-by-one 修复（查崩溃层本身）解决了节点重复执行，但漏了「跳过节点的输出如何恢复」。adversarial reviewer 用「crash between saveNodeOutput and saveBarrier」的时序攻击暴露了这个 gap——单靠 correctness reviewer 看 recover() 代码是看不出来的，需要构造跨组件的失败链。

---

### P0-2: timeout abort stray COMPLETED 防护未实现（ADV-2）

详见 [02-bugs-and-fixes.md Bug-5](./02-bugs-and-fixes.md#bug-5p0-timeout-abort-的-stray-completed-防护未实现--adv-2)。

**为什么这个发现有价值**：典型的「Javadoc 承诺了但代码没实现」——文档与代码不一致。testing reviewer 先发现（看 Javadoc 觉得该有防护，grep 代码发现没有），reliability reviewer 独立确认，adversarial 构造了完整攻击链，correctness 第四个佐证。这种问题单测覆盖不到（因为测的是「承诺的行为」），只有 review 能抓。

---

### P2: workflow_checkpoints 缺 UNIQUE 约束 + saveBarrier 无 ON CONFLICT

**发现**：reliability reviewer（REL-003）+ adversarial（ADV-3）+ correctness（F2）三方确认。
**问题**：`workflow_checkpoints` 表只有 `id BIGSERIAL PK`，没有 `UNIQUE(workflow_id, super_step)`；`saveBarrier` 是 plain INSERT 无 `ON CONFLICT`。重放/重试时同一 super-step 会插入重复行，`findLatestBarrier`（`ORDER BY super_step DESC LIMIT 1`）可能返回陈旧快照。
**对比**：`workflow_node_outputs` 表有 `UNIQUE(workflow_id, super_step, node_id)` + `ON CONFLICT DO UPDATE`，幂等设计完整。barrier 表漏了同样设计。
**修复**：V1 migration 加 `CONSTRAINT uq_workflow_checkpoint UNIQUE (workflow_id, super_step)`；saveBarrier SQL 加 `ON CONFLICT (workflow_id, super_step) DO NOTHING`（barrier 数据不可变，重放直接忽略）。
**为什么有价值**：这是 schema 设计的一致性问题——同一子系统两个表，一个幂等一个不幂等。data-migration reviewer 也独立指出了索引/约束的对称性。体现了「多视角审查能发现单一视角遗漏的一致性问题」。

---

## Review 方法论的面试价值

ce-code-review 的核心不是「找 bug」，是「用不同视角的 reviewer 交叉验证」。这套机制的价值：

1. **Persona 分工避免盲区**：correctness 看「逻辑对不对」，adversarial 看「怎么构造场景打破它」，reliability 看「依赖挂了怎么办」，data-migration 看「schema 对不对」。一个 reviewer 看不出的问题，另一个视角能发现。
2. **Cross-reviewer agreement 提权**：同一问题被 N 个独立 reviewer 报告，confidence 提升。U5 的两个 P0 被 4 个 reviewer 独立确认——如果只有 adversarial 报，可能是它构造的场景太极端；但 correctness/reliability/testing 都从各自视角独立得出同样结论，可信度就极高。
3. **Confidence 门控**：anchor 50 以下的 finding 被 suppress（除非 P0），避免低信号噪音淹没真问题。

**面试讲法**：「我的 code review 不是人肉 review，是用多 agent 编排做的——10 个 persona reviewer 并行，每个有专属视角（逻辑正确性、安全、混沌攻击、数据迁移等），独立报告后交叉验证。这模仿了真实团队里不同角色（SRE、DBA、安全工程师）的 review 视角，但能并行跑、且每个 reviewer 都是深度专家。」

---

## 已知未修的 Residual Risks（记录备查，非合并阻塞）

这些是 advisory 级，记在 `docs/handoff/u5-checkpoint-recovery.md`，演示前定即可：

- **ADV-4**: Recovery 无版本检查——崩溃后 workflow 定义变了（nodeId 同但语义不同），Recovery 仍按旧 nodeId 跳过。v1 静态 DAG 无此场景，U8 建 `workflow_definitions` 表后补。
- **ADV-5**: 并发 recovery 双执行——两个 JVM 同时 recover 同一 workflowId，无锁，会 LLM 双计费。v1 单实例不触发，分布式是 v1.1 stretch。
- **ADV-6**: InMemory vs Postgres 数值类型分歧——JSONB 往返 `Long 100L` → `Integer 100`，下游强类型断言会在 PG 路径 ClassCastException。需 PG 集成测试覆盖（当前 PG 零测试，见下）。
- **ADV-7**: `BspEngine.saveNodeOutput` 失败被 warn-only 吞掉——DB 瞬时故障 → 节点未持久化 → 下次 recovery 重跑 → LLM 重复计费。根因是 U2 代码（非 U5 diff），记为跨单元 follow-up。
- **REL-002**: `JdbcTemplate` 无 `setQueryTimeout`——DB 操作可无限阻塞 VT。生产前加 `jdbc.setQueryTimeout(5)`。
- **PG 零测试**: `PostgresCheckpointManager` 的 Semaphore/ON CONFLICT/JSONB/Flyway 全未测试（当前只用 InMemory 测）。需 H2 或 Testcontainers 补集成测试。这是 U5 最大的测试缺口。

**面试讲法（被问「还有什么没做好」时）**：「我清楚知道哪些是 known risk：比如 PostgresCheckpointManager 还没有集成测试（只有 InMemory 覆盖），Recovery 的版本检查依赖 U8 的 workflow_definitions 表还没建。这些不是 bug，是 v1 范围外的 stretch，我记在 handoff 文档里，演示前补。」——主动暴露 known gap 比假装完美更可信。
