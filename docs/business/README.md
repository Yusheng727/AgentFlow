# 业务文档集

## 目的

AgentFlow 仓库核心业务流程/规则/状态流转的可追溯文档，供研发、测试、产品、架构师与 AI Agent 使用。每条关键业务结论带源码证据与可信度标记。

## 覆盖范围

已覆盖（flows/ 下，2026-08-25/26 两批共 11 条——全部已识别业务能力）：

第一批（2026-08-25，核心四链路）：

- [工作流生命周期（提交到终态）](flows/workflow-lifecycle.md)
- [人工审批（HITL）全链路](flows/hitl-approval.md)
- [崩溃恢复与重试](flows/crash-recovery-retry.md)
- [动态路由与循环](flows/dynamic-routing-and-loops.md)

第二批（2026-08-26，支撑七能力）：

- [工具授权与 API 准入（权限域）](flows/tool-authorization.md)
- [Kafka 异步分发（提交/执行解耦）](flows/kafka-async-dispatch.md)
- [工作流版本管理（定义持久化与版本冲突）](flows/workflow-versioning.md)
- [成本与预算控制（LLM 花费治理）](flows/cost-budget-control.md)
- [可观测与诊断（指标、轨迹、异常诊断、干跑）](flows/observability-diagnosis.md)
- [敏感数据列加密（静态加密 R22）](flows/column-encryption.md)
- [RAG 检索增强（向量检索扩展点）](flows/rag-retrieval.md)

未覆盖：无（[business-capability-catalog.md](business-capability-catalog.md) 11 项全部文档化；新能力出现时按增量更新模式补录）。

## 生成信息

- 生成时间：2026-08-25（首批 4 条）/ 2026-08-26（第二批 7 条，CodeGraph 复核 + 测试断言 B 级交叉验证贯穿两批）
- 代码版本：main@2a37d6b
- 生成方式：business-flow-documenter skill（取证优先级 1 = CodeGraph：`.codegraph/` 索引 266 文件 / 5,068 节点 / 11,949 边）

## 可信度说明

- 已确认：A 级证据（源码、配置、CodeGraph 路径）直接支持，或 A 级与 B 级（测试断言、DDL）交叉验证
- 合理推断：多处间接证据，缺完整闭环
- 待确认：代码不足以确定，见 [open-questions.md](open-questions.md)

## 如何增量更新

分支/PR 变更后，对受影响流程执行 skill 的增量更新模式：`git diff --name-only` 定位变更 → 通过调用链找受影响流程文档 → 仅更新相关文档并在其顶部追加变更记录 → 同步 evidence-index/catalog/glossary/domain-map → 运行 `validate_business_docs.py` 校验。

## 如何反馈与修正

发现文档与代码/业务不符：修正文档对应结论与证据 → 在文档变更记录注明修正原因 → 重新校验。证据失效（文件删除/重构改名）时优先更新文档第 13 节与 evidence-index.md。
