# AgentFlow 简历成稿模板 + 投递策略

> 成稿时间：2026-08-26 ｜ 依据：本目录 01-03 号调研报告 + `docs/developer-notes/00-interview-arsenal.md`（面试叙事）+ `docs/plans/agentflow/07-sources-revision-interview.md`（30s/5min 自述稿）
> 用法：两个版本按投递方向选用；每条 bullet 都要能扛住三层追问（自测法见 02 号文档第 11 条）。

## 一、项目客观数据（写进第一屏的可信度锚点）

- GitHub：`https://github.com/Yusheng727/AgentFlow`
- 13 个 Maven 模块 + React UI（6 Tab）
- 173 commits（2026-06 起步，约 3 个月）
- 主代码 118 个 Java 文件 / 约 1.13 万行；92 个测试文件 / 500+ 测试
- JaCoCo 80% 覆盖率门禁（INSTRUCTION/BUNDLE 维度）+ GitHub Actions CI 全绿
- 真实 DeepSeek 端到端验证（真实 token 计费落 Prometheus/Grafana）

## 二、版本 A：投 AI 应用 / Agent 开发岗（Agent 能力前置）

> **AgentFlow — Java 原生 Multi-Agent 编排引擎**（个人项目 | 2026.06–至今）
> `github.com/Yusheng727/AgentFlow` ｜ 13 个 Maven 模块 ｜ 173 commits ｜ 500+ 测试 / JaCoCo 80% 门禁 / CI 全绿
>
> - **从 0 实现 BSP 执行引擎**：YAML DSL 声明 DAG → 最长路径分层 → Virtual Threads 层内并行 + barrier 确定性合并，实现免锁并发与确定性可测；扩展条件路由（`when` 谓词 + 可达性剪枝）与有界循环（回边 + 轮次重放）均不破坏 BSP 不变量
> - **两级 Checkpoint 崩溃恢复**：节点级 COMPLETED 同步写防 LLM 重复计费 + 恢复期路由决策 BFS 重放；修复评审发现的 off-by-one 层定位缺陷与 stray COMPLETED 防护缺口（2 个 P0，4 名 reviewer 独立确认）
> - **HITL 人工审批全链路**：引擎暂停（上下文快照落库）→ REST/Web UI 审批 → 从暂停层续跑，防重复计费设计贯穿三条恢复路径（崩溃/审批/重试共享同一骨架）
> - **双框架适配器实证可移植性**：Spring AI 2.0 与 LangChain4j 收敛在同一个 `AgentFunction` 窄接口，core 模块零 LLM 框架依赖——换框架只动一个适配器类
> - **生产化工程**：Kafka 提交/执行解耦（`tryClaim` 原子幂等防重复消费双计费）、5 处敏感列 AES-256-GCM 加密（生产 fail-closed）、Micrometer 指标 + Grafana 6 面板、真实 DeepSeek 端到端验证（真实 token 计费落 Prometheus）

## 三、版本 B：投 Java 后端岗（工程化前置，前两条替换）

> 其余三条同版本 A，前两条换成：
>
> - **并发模型设计**：BSP（Bulk Synchronous Parallel）执行引擎——同层节点 Virtual Threads 并行 + `CompletableFuture.allOf` 层间 barrier，只读快照保证层内互不可见，声明序 Reducer 解决并发写冲突（免锁设计）
> - **分布式解耦与幂等**：Kafka 提交/执行分离（单条条件 UPDATE 原子抢占执行权，at-least-once 下防重复计费）；提交守卫事前拦截失控 DAG（节点数/预估成本 422）+ per-workflow 预算事后告警两层防护

## 四、为什么主打这些要点（依据链）

- **JD 逐字对齐**（01 号文档）：Agent 岗核心措辞「任务中断、异常恢复、运行状态持久化」= Checkpoint/Recovery/HITL；「用户授权、敏感操作确认、风险拦截、审计日志」= 工具授权/守卫/脱敏/加密
- **S 级项目判据**（02 号文档第四节第 5 条）：自研编排引擎命中「工程化完整 + 底层原理理解」
- **面试官三灵魂拷问之一**：「你是调包侠，还是造轮子的人？」——AgentFlow 是后者且可深挖
- **避雷**：不写「精通」（02 号文档雷区 1）；不堆关键词（雷区 4）；叙事重心从「系统多大」换成「闭环多严」（雷区 7 的对策）

## 五、「AI 辅助开发」口径

**主动说，不回避**（诺姆四达 JD 原文把「Claude Code/Codex/Cursor 实际使用经验」列为加分项——AI 辅助工程能力本身已经在变成 JD 技能）：

> 「AI 负责写代码，我负责架构决策和质量把关。」

证据链：173 commits 的演进节奏、每轮 ce-code-review（10 persona）修复记录（`docs/developer-notes/03-review-findings.md`）、真实环境验证记录（DeepSeek E2E、真 PG verify、真 Kafka E2E）。

## 六、面试弹药衔接（已有文档）

| 场景 | 文档 |
|---|---|
| 30 秒 / 5 分钟自述稿 | `docs/plans/agentflow/07-sources-revision-interview.md` 档 C |
| 每个单元的追问钩子 | `docs/developer-notes/00-interview-arsenal.md` |
| 7 条追问链 → 弹药位置地图 | `docs/learning/Agentflow-code/learning-summaries/interview-question-coverage-map.md` |
| 已知边界（Q1-Q14，被追问「哪里没做好」用） | `docs/learning/Agentflow-code/open-questions.md` |

**面试前必做三件事**：
1. 把 Q3（守卫为什么是 500）/ Q7（恢复触发路径）/ Q8（恢复并发防护）三个 P1 想好「已知边界 + 改进思路」版本回答
2. 跑一遍 `git log --stat` 重温开发顺序，确保口头叙事与提交历史吻合（验真第 5 条）
3. 选定一个踩坑故事主打：预算记账「只记成功路径末次」低估 ≤3x → review 抓到 → 改逐轮记账（「能不能说出踩过的坑」是面试官判断真做没做过的头号标准）

## 七、投递策略（三项目矩阵）

| 投递方向 | 项目排序 | 关键词策略 |
|---|---|---|
| AI 应用 / Agent 岗 | InterviewCoach ≈ AgentFlow 双主项目，ToyRush 收缩 | AgentFlow 打「工程化完整」差异点——竞品是「10 个面 AI 岗 9 个做了基础 RAG 问答」的 B 级项目 |
| Java 后端岗 | ToyRush 前置（补 Spring Cloud/Redis），AgentFlow 作工程深度背书 | AgentFlow 用版本 B；八股（JVM/并发/集合）另行准备，项目覆盖不了 |
| 校招 Agent 岗（如立邦型） | AgentFlow 前置 | 直接满足「可展示代码仓库、Demo」优先项 |

**已知缺口与对冲**（01 号文档第二节）：
- **Python**（AI 岗 3/3 提到）：口径「Java 生态 AI 工程化是差异化定位（对标 LangChain4j/Spring AI 生态位），应用层 AI 实践在 InterviewCoach」
- **MCP**（2/3 AI 岗提到，加分项）：可补一个最小 MCP 接入，性价比高
- **Spring Cloud/Redis/MyBatis**：由 ToyRush 承接，AgentFlow 不硬凑
