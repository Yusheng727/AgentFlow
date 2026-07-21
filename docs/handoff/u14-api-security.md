# U14 接手清单 — API 鉴权 + 凭证管理 + WorkflowController

> 给下一个 Claude Code 会话：在 `feat/u14-api-security` 分支上，代码已写完但未跑 `mvn verify`（环境无 Maven）。最后更新：2026-07-21。

## 起点

- **分支**：`feat/u14-api-security`（本地，基于 main `b4a163b`）
- **已就位**：17 个文件（8 生产 + 1 迁移 + 4 修改 + 5 测试），代码已写完
- **依赖模块**：agentflow-core / agentflow-api（已就位 spring-boot-starter-web）
- **基线**：`mvn verify` 5 模块 SUCCESS（136 tests，JaCoCo 80%）

## 文件清单

### 新增生产文件

| 文件 | 模块 | 说明 |
|:---|:---|:---|
| `db/migration/V2__add_created_by.sql` | core | ALTER TABLE workflow_executions ADD created_by TEXT |
| `security/CredentialManager.java` | core | LLM API Key 管理（从 env 读取，禁止 yml 硬编码） |
| `security/PromptRedactionFilter.java` | core | 敏感数据正则脱敏（API Key / 手机号 / 身份证） |
| `api/security/ApiKeyAuthFilter.java` | api | OncePerRequestFilter：X-API-Key → SHA-256 → callerId |
| `api/security/WorkflowOwnershipChecker.java` | api | workflow created_by 校验（防 IDOR，403） |
| `api/security/CallerToolAllowlist.java` | api | per-caller Tool 授权（config 映射） |
| `api/WorkflowController.java` | api | POST /workflows（202 异步）+ GET status + POST retry |

### 修改已有文件

| 文件 | 变更 |
|:---|:---|
| `checkpoint/CheckpointManager.java` | 扩展：+ findLatestBarrier / findCompletedNodes / initWorkflow(4-param) / updateStatus / findCreatedBy / findStatus |
| `checkpoint/NoopCheckpointManager.java` | 实现新增方法（空操作） |
| `CLAUDE.md` | 更新进度 + U14 行 |

### 测试文件

| 文件 | 场景数 | 覆盖 |
|:---|:---:|:---|
| `core/.../security/PromptRedactionFilterTest.java` | 8 | API Key / Bearer / 手机号 / 身份证 / 混合 / null |
| `core/.../security/CredentialManagerTest.java` | 6 | 硬编码检测 / 空 Key 抛异常 / Spring Environment 回退 |
| `api/.../security/ApiKeyAuthFilterTest.java` | 8 | 401(无 Key/无效/空) / 有效放行 / 非/api 跳过 / SHA-256 |
| `api/.../security/WorkflowOwnershipCheckerTest.java` | 8 | 本人/他人/不存在/IDOR 场景/多 workflow 隔离 / callerId 提取 |
| `api/.../security/CallerToolAllowlistTest.java` | 7 | 授权/未授权/未知 caller/通配符/空 allowlist/null 参数 |

## 核心设计决策

### 1. ApiKeyAuthFilter 鉴权链
- X-API-Key header → SHA-256 hash → 写 request attribute `callerId`
- 仅拦截 `/api/**`，非 API 路径跳过
- v1 硬编码 Key 集合（构造注入 Set<String>），v1.1 升级 DB

### 2. WorkflowOwnershipChecker 防 IDOR
- `isOwner(workflowId, callerId)` 查 `workflow_executions.created_by`
- `requireOwnership` 抛 `OwnershipException` → Controller 统一返回 403
- 保护：GET /status / POST /retry / GET /trace（U6/U7 端点）

### 3. CredentialManager 凭证安全
- 从 `System.getenv` + Spring Environment 读取 LLM Key
- 启动时 `validate()` 检测：空 Key → 启动失败；硬编码占位符 → 启动失败
- 禁止在 `application.yml` 中写死 Key

### 4. PromptRedactionFilter 脱敏
- 纯文本工具（core 层，不依赖 Spring AI Advisor API）
- adapter 层可包装为 `PromptRedactionAdvisor` 注入 Chain
- 模式：sk-* / Bearer * / 手机号 138****5678 / 身份证 110101********1234

### 5. WorkflowController 异步执行
- POST 立即返回 202，BspEngine 在 VT executor 中异步跑
- retry 端点 v1 简化（从头执行），U5 RecoveryProtocol 落地后改为增量恢复

### 6. CallerToolAllowlist
- 空 allowlist = 全局允许（向后兼容，不启用工具授权时）
- 通配符 `*` = 该 caller 全部 Tool 放行

## 合并冲突预警

U5 分支（`feat/u5-checkpoint`）也修改了 `CheckpointManager` 接口。关键差异：

| 方法 | U5 版本 | U14 版本 | 合并策略 |
|:---|:---|:---|:---|
| `initWorkflow` | 3 参数 (wfId, name, version) | 4 参数 (+ createdBy) | **取 U14**（4-param，兼容性更广） |
| `findLatestBarrier` | 强类型返回 `Optional<BarrierCheckpoint>` | 泛型 `Optional<?>` | 取 U5（强类型更好） |
| `findCompletedNodes` | 强类型返回 `List<NodeOutputStore>` | 泛型 `List<?>` | 取 U5 |
| 新增 `findCreatedBy` / `findStatus` | - | U14 新增 | 保留 |
| `updateStatus` | `(wfId, WorkflowStatus)` 枚举 | `(wfId, String)` | 取 U5 枚举版 |

**建议**：先合 U5 到 main，再合 U14 时按上表 resolve CheckpointManager 冲突。

## 验证命令

```bash
cd AgentFlow
git checkout feat/u14-api-security

# 编译
mvn -s settings.xml -B -ntp compile

# 测试
mvn -s settings.xml -B -ntp test

# 完整验证
mvn -s settings.xml -B -ntp verify
```