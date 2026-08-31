# 导学-FNA工作台（OrbitPOST · 发票 AI 识别 + 人工双控过账工作台）

> 素材来源：`C:\Users\YushengWang\project\learning\CodeLearning\fnaworkbench\yusheng-feat-integrate-ms-auth\`（代码学习档案）+ `C:\Users\YushengWang\project\learning\Business\FNA workbench\`（业务文档）
> 分析基线：`yusheng/feat-integrate-ms-auth@9b578d4`（集成分支）｜整理时间：2026-08-31

## 1. 前置知识（面试高频标注）

| 知识点 | 为何需要 | 在本项目中的位置 | 高频度 |
|---|---|---|---|
| FastAPI 依赖注入（Depends） | 全局鉴权的实现机制，一句 `include_router(dependencies=[Depends(require_user)])` 给全部业务路由上锁 | `app/main.py:59` + `app/auth/__init__.py` | ★★★ |
| JWT 验签 vs Token 内省 | 双轨登录的核心差异：本地 JWT HS256 可本地验签；Microsoft Graph token 无法本地验签，只能"携 token 调 /me 证真再读声明" | `app/auth/__init__.py:60-90` | ★★★ |
| 表驱动状态机（数据驱动引擎） | 面试高频设计题；本项目 7 态 13 流转全部存表，引擎只做查表+守卫+落库 | `app/services/flow_engine_service.py:198-205` | ★★★ |
| 幂等性设计 | 幂等键先查后插 + DB 唯一约束兜底，防双击重复提交；事件溯源的最小可用形态 | `flow_engine_service.py:258-263` | ★★★ |
| 异步任务轮询模式 | 上传同步建任务不同步等结果；后台协程 10s 轮询外部 AI 服务回写 | `app/services/extraction_poller.py` | ★★★ |
| SQL 事务边界 | 每个业务动作单事务原子落库（票更新+流程节点+审计事件同 commit） | `flow_engine_service.py:320-363` | ★★☆ |
| Vue3 组合式 API（watch/route） | URL 驱动视图态的组件侧核心：双 watch + immediate 保证刷新即恢复 | 四队列组件 `watch(route)` | ★★☆ |
| pdfjs 文本层坐标计算 | PDF 高亮不需要 OCR：`getTextContent()` 的 transform 矩阵算页面坐标，百分比化后与缩放解耦 | `PdfHighlight.vue:72-89` | ★★☆ |
| RBAC 与权能（capability）模型 | 身份不是账号属性而是动作上下文：双权能账号 submit 时是 MAKER、approve 时是 CHECKER | `user_auth_service.caps_of` + `flow_engine_service.py:254-256` | ★★☆ |
| append-only 事件日志 | 只插不改，"当前状态"是日志的最新投影——审计要求的实现范式 | `flow_node_record` 表 + `_record_node` | ★★☆ |
| SQLAlchemy 2.x（Mapped/select） | 后端持久层风格；DAO 层只拼 where 不 commit | `app/dao/models.py`、`ticket_dao.py` | ★☆☆ |
| Vue 竞态处理（AbortController） | 换文档 abort 旧 fetch 防 race；权限注入"不清详情态"防打回列表 | `PdfHighlight.vue:199-228`、`store.setCaps` | ★☆☆ |

## 2. 重点亮点与学习顺序（先看这个）

| 亮点标题 | 为什么重要 | 通用技术关键词 | 先看哪些文件 | 建议学习顺序 |
|---|---|---|---|---|
| ① 数据驱动的审批流状态机 | 全系统骨架：7 态 13 流转全部配置化，加流转不改引擎代码；守卫链（角色白名单/必查全标/自审拦截）可扩展 | 表驱动设计、Guard SPI、幂等、append-only 审计 | `flow_engine_service.py`（492 行全读）→ 业务文档 `invoice-processing-and-review.md` | 1 |
| ② 双轨鉴权与权能体系 | 认证（MSAL+JWT）与授权（caps→菜单/数据/动作）三个正交维度分离；"安全检查在决策点做，不在数据同步点做" | JWT vs 内省、RBAC、capability、SSO | `app/auth/__init__.py` → `user_auth_service.py` → 前端 `auth/store.js` + `AppLayout.vue` | 2 |
| ③ 异步 AI 提取管道 | 与不可控外部系统打交道的完整范式：三段式事务、指数退避重试、轮询回写、失败终局 | 异步解耦、重试退避、最终一致、轮询 Job | `ticket_service.create_extraction_task` → `extraction_poller.py` → `doc_agent_util.py` | 3 |
| ④ 自研 PDF 查看器与精准高亮 | 前端算法密集亮点：文本层坐标计算、四级匹配降级链、百分比坐标与缩放解耦 | 文档即数据结构、策略降级、坐标变换 | `PdfHighlight.vue`（331 行全读）→ 业务文档 `queue-list-and-navigation.md` | 4 |
| ⑤ URL 驱动视图态 | 刷新/分享/回退全部自然恢复：详情态进路径、筛选进 query、组件 watch 路由反向同步 | 单一事实来源、状态外置、前端路由 | `navConfig.js` → `queueQuery.js` → 四队列组件双 watch | 5 |
| ⑥ 前后端镜像纯函数契约 | JSON path 提取前后端各写一份刻意同构（后端注释直说"与前端逻辑一致"）——跨端契约的同构纯函数+各自单测维护法 | 契约测试、纯函数、可单测性 | `ticketUtils.js#extractField` vs `ticket_service._extract_by_rule` | 6 |

## 3. 必备知识点 checklist

- [ ] 能画出七态状态机全图（AI_PROCESSING → READY_FOR_MAKER → READY_FOR_CHECKER ⇄ REJECTED_BY_CHECKER → READY_FOR_POST → POSTED；AI_FAILED 分支）
- [ ] 能解释三种守卫指令（perm 角色白名单 / all_required_marked 必查全标 / maker_ne_checker 自审拦截）各自防什么
- [ ] 能解释幂等键的"先查后插 + DB 唯一约束兜底"两层防重
- [ ] 能解释为什么 Checker 不能覆盖 Processor 数据（防无意覆盖，非防篡改）
- [ ] 能解释 Graph token 为什么只能内省不能本地验签
- [ ] 能解释 caps 权能模型 vs 单一角色的差异（双权能账号的动作上下文身份）
- [ ] 能画出上传→AI 提取→轮询回写的时序（哪步同步、哪步异步、失败去哪）
- [ ] 能解释 PDF 高亮的四级匹配降级链与每级适用场景
- [ ] 能解释"金额字段强制手填"这类内控规则的前后端分工
- [ ] 知道数据权限在 SQL 侧过滤（processor 只看自己的票、checker 看组内排除自己）

## 4. 推荐阅读（结合素材档案）

| 主题 | 通用技术点 | 建议阅读位置（相对素材根） | 预计时间 | 读完能回答什么 |
|---|---|---|---|---|
| 系统全景 | 进程边界/入口清单/外部依赖 | `system-overview.md` | 20min | 系统有几层、边界在哪、无缓存无 MQ 意味着什么 |
| 模块地图 | 按业务能力组织的模块划分 | `module-map.md` | 15min | 每个模块的风险等级与上下游 |
| 技术架构 | 分层依赖/设计模式/幂等事务 | `technical-architecture.md` | 30min | 8 个设计模式各自落在哪个类 |
| 状态机引擎 | 表驱动+守卫+幂等+留痕 | `implementation-walkthroughs/ticket-state-machine.md` | 40min | advance 一笔请求的完整生命周期 |
| 鉴权体系 | 双轨登录→caps→三权限维度 | `implementation-walkthroughs/auth-caps-permissions.md` | 40min | 为什么安全检查放在路由守卫而非 setCaps |
| 上传提取管道 | 三段事务+轮询回写+失败终局 | `implementation-walkthroughs/upload-extraction-pipeline.md` | 40min | 卡 AI_PROCESSING 的根因（跨事务非原子） |
| PDF 高亮 | 四级匹配+坐标百分比化 | `implementation-walkthroughs/pdf-viewer-highlight.md` | 30min | 为什么坐标要百分比化、旋转 PDF 怎么对齐 |
| 审批业务规则 | 27 条 R 规则带证据 | `Business/FNA workbench/flows/invoice-processing-and-review.md` | 30min | 每条内控规则（金额手填/自审禁令/防覆盖）的业务动机 |
| 个人学习总结 | 第一人称误解修正史 | `learning-summaries/fna-workbench-overall.md` | 20min | "我曾以为…后来发现…"的面试素材 |
| 技术决策 ADR | 取舍与被否选项 | `technical-decisions/`（4 篇） | 40min | 为什么 URL 方案否掉 query 参数、为什么自研 PDF 查看器 |

## 5. 自学提醒

若某文件或原理看不懂，请继续追问 AI；本技能负责给学习路径与题目，不提供逐行讲解。

## 6. 项目技术定位

**全栈偏后端**（后端：FastAPI 分层 + 状态机引擎 + 鉴权；前端：Vue3 工作台 + 自研 PDF 查看器）。依据：核心复杂度在后端 flow_engine_service（492 行引擎）与 auth 体系，前端是密集交互工作台（四队列 + PDF 分屏）。

## 7. 核心原理解析

1. **表驱动状态机：为什么规则进表不进代码**
   问题 → 13 条流转规则散在代码里时，每次调流程（加环节/改角色）都要改代码发版。
   机制 → `state_transition_def` 表存 from_state/action/to_state/actor_roles/guards/route_key；引擎运行时查表，无合法转移抛 409；守卫是 JSONB 里的字符串指令由解释器执行，新守卫只改数据。
   落点 → `flow_engine_service.py:198-205`（查表）+ `:169-185`（守卫解释器）。学到边界：解释器指令集越少越可控，超出三种指令该考虑真工作流引擎。

2. **幂等两层防重：先查后插 + DB 约束兜底**
   问题 → 用户双击提交会重复流转、重复留痕。
   机制 → advance 收幂等键：查到同键记录直接返回已建节点（created=false）；并发窗口由 `uk_fnr_idem` 唯一约束兜底（第二个插入报错）。
   落点 → `flow_engine_service.py:258-263` + `models.py:283`。学到的最小事件溯源形态：flow_node_record 只插不改，"当前状态"是日志最新投影。

3. **异步提取的跨事务非原子：三段式事务的代价**
   问题 → 上传要落库 + 调外部 AI 服务，外部调用不可放进 DB 事务。
   机制 → 三段 commit：①票+Job 落库 ②调 DocAgent 建任务 ③回填 execution_id。接口快速返回，结果由 poller 10s 轮询回写。
   落点 → `ticket_service.py:86-155`。代价 → ②失败时票已存在但 execution_id 为空，poller 跳过无 execution_id 的 job——票永久卡 AI_PROCESSING（这正是学习总结里"状态机转移完整 ≠ 下游消费者被重新触发"的活例）。

4. **Graph token 内省：先证通道可信再读数据**
   问题 → 微软不给 Graph token 的本地验签公钥（只给 ID token），无法 HS256 验签。
   机制 → 携 token 调 Graph /me：200 则证真，从未验签 payload 读声明；内省结果进程内缓存（按 exp 过期，1000 条清空）。
   落点 → `app/auth/__init__.py:42-57`。本质：把"验证令牌"转化为"验证服务端承认这个令牌"。

5. **身份是动作上下文：权能模型 vs 单一角色**
   问题 → 双权能账号（既制单又复核）无法用单一 role 表达。
   机制 → caps 是账号属性并集（processor/checker），但"等效角色"在每次动作时由 caps+action 推导：submit 时是 MAKER、approve 时是 CHECKER；配合 maker_ne_checker 守卫，同一账号不能对同一张票既提交又复核。
   落点 → `flow_engine_service.py:254-256`。精妙处：安全边界在动作点判定，而非登录时刻一次性授权。

6. **PDF 高亮的"文档即数据结构"：不需要 OCR**
   问题 → 复核要"点开即看到 AI 值在原件的位置"。
   机制 → PDF 文本层本就结构化：`getTextContent()` 每个字符串的 transform 矩阵 + viewport 矩阵 → 页面坐标 → 行聚类成矩形 → 百分比化与缩放解耦。匹配按四级降级：空格拼接 indexOf → 无空格紧凑 → 正则跨空白 → token 容错（跳 1 词/覆盖 80%），每级打分取最优。
   落点 → `PdfHighlight.vue:72-163`。旋转 PDF 用 viewport 默认值（含 page.rotate 元数据）与 Adobe 行为对齐。

## 8. 关键设计决策

| 决策 | 备选 | 取舍 | 风险 | 验证 |
|---|---|---|---|---|
| 状态机规则进数据库表（state_transition_def） | 代码 switch/状态模式/引入工作流引擎（Camunda 等） | 规则即数据：调流程只插表行；代价是要自己写守卫解释器，且种子数据质量决定引擎正确性 | 种子数据配错 → 非法流转；解释器指令集膨胀 | 14 个 flow engine 测试用例（全主路径+负路径）；state_transition_def 种子 SQL |
| 详情态走 URL 路径段（/fna/maker/{no}） | query 参数、localStorage、Pinia 状态 | 刷新/分享/回退自然恢复；被否方案：query 参数（可读性差） | 状态外置后每处写状态都要审计竞争——三处防竞争注释 | 组件双 watch + immediate；刷新恢复手动验证 |
| Graph token 内省而非拒用 | 要求全部用户走本地 JWT | 保住 SSO 体验；代价是每次内省一次网络往返（用进程内缓存对冲） | 内省缓存有界清空（1000 条全清）的突刺 | 缓存按 exp-60s 过期测试 |
| 自研 PDF 查看器（pdfjs-dist） | 第三方查看器组件/iframe embed | 需要字段级高亮+鉴权 header，embed 带不了 Authorization；自研可控 | 算法密集：四级匹配、CMap 字体坑（中文 PDF 空白——commit 7d33ee9 修复） | 四级匹配各有测试； AbortController 竞态防护 |
| Checker 直批（移除 Decision 逐字段勾选） | 保留 Decision 勾选前置拦截 | 复核效率优先；退役组件+纯函数刻意冻结保留"防将来恢复" | 无前置一致性校验，依赖 Checker 自行对照 | 防覆盖测试：reject 传入篡改 manual_fields 不生效 |
| 金额字段强制手填（不预填 AI 值） | 预填 AI 值允许修改 | 内控要求：金额必须人工对照 PDF 录入 | 留空提交仅拉低准确率不拦截（g2 守卫有意不实现） | R2/R23 规则带业务文档证据 |

## 9. 量化与验证（含待测，建议）

建议的验证方式（素材档案中暂无实测数据，标待测）：

- **状态机正确性**：已有 14 个 flow engine 测试（全主路径+负路径）——建议补种子数据与代码行为的一致性核对（Q7 待确认项）。
- **列表查询次数账单**：每票 2 次 flow 查询（statusTime/notes）× 每页 12 票 = 24 次额外查询——建议画查询次数账单并在百票规模压测（Q6：spec 承诺不 N+1 但实现逐票查询，待确认）。
- **PDF 高亮四级命中率**：建议对真实发票语料统计每级匹配的命中分布，验证降级链的必要性。
- **上传幂等**：当前无幂等键（连点两次会建两批票，仅按钮 loading 防抖）——建议补客户端请求 ID。
- **准确率口径**：前端旧口径（分母=全部字段）与后端权威口径（分母=op 或 ai 非空）不同——面试可讲"为什么口径要以服务端为准"（b969898 修 accuracy 分母逻辑的 commit）。
- **%Captured 与 accuracy 双指标**：%Captured 度量 AI 提取质量、accuracy 度量人工修正幅度——建议用真实运营数据验证两指标是否负相关（AI 越准人工改得越少）。
