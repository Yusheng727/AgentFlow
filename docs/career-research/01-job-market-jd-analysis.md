# 就业市场调研（2025-2026）

> 调研时间：2026-08-26 ｜ 来源：猎聘 Web 端（一手，7 份完整 JD + 约 60 条列表）+ BOSS直聘 Web 端（未登录态，仅技能标签）
> 用途：判断 AgentFlow 的技术栈命中度，指导简历投递策略与缺口补齐优先级。
> 局限：校招完整 JD 样本偏少（立邦 1 条）；牛客 JD 汇总帖未采集；BOSS直聘登录墙截断。

## 一、按岗位类型分组的技能要求汇总

### A. 传统 Java 后端（3 个完整 JD：兴业数金、萤火虫网络、诺姆四达基础要求段）

| 技能 | 出现频次 | 措辞示例 |
|---|---|---|
| Spring Boot | 3/3 | 「熟练使用Spring Boot、Spring Cloud/Alibaba等主流开发框架」 |
| Spring Cloud/Alibaba | 3/3 | 同上——**Java 后端 JD 的标配，无一例外** |
| MySQL/PostgreSQL | 3/3 | 「具备数据库设计、SQL编写与索引优化能力」 |
| Redis | 3/3 | 「缓存、分布式锁、热点数据优化等」 |
| 消息队列 (Kafka/RabbitMQ) | 2/3 | 「了解消息队列的基本原理与使用场景」 |
| MyBatis | 2/3 | 「熟悉MyBatis等ORM框架」 |
| 分布式/微服务概念 | 3/3 | 「了解分布式系统设计、微服务架构」 |
| JVM/并发/集合基础 | 2/3 | 「精通集合、多线程、IO、JVM基础原理」 |
| Linux/Docker/Git | 3/3 | 常规工具链 |
| 前端了解 (Vue/React) | 2/3 | 「了解Vue/React等常用前端框架」 |

AI 关键词在此类 JD 出现比例：1/3（诺姆四达专门加了「AI应用方向」变体，其余 2 条纯后端无 AI 要求）。

### B. AI 应用开发 / 大模型应用（3 个完整 JD：诺姆四达 AI 段、和生创新、捷科智诚）

| 技能 | 出现频次 | 措辞示例 |
|---|---|---|
| Agent/智能体系统 | 3/3 | 「基于LLM的智能体系统，具备任务规划、工具调用、记忆管理、对话控制」 |
| RAG / 向量检索 / Embeddings | 3/3 | 「LLM API集成、Prompt Engineering、RAG检索增强」「向量检索、智能问答」 |
| LLM API 调用 | 3/3 | 「大模型API、工具调用机制和业务接口」 |
| Prompt Engineering | 2/3 | 「高质量提示词编写能力」 |
| Python | 3/3 | 「熟练使用Python」（2 条列为主语言，1 条「优先Python」） |
| 多线程/异步并发 | 2/3 | 「具备多线程/异步并发开发能力」 |
| MCP | 2/3 | 「AI Agent、AI工作流、RAG、MCP等项目实践经验」 |
| Spring AI / Java 原生 AI 框架 | 1/3 | 「了解Spring AI等Java原生AI框架，有AI Agent/智能体相关实践经验者优先」——**全网唯一一条点名 Spring AI 的 JD** |
| AI Coding 工具（Claude Code/Cursor） | 1/3 | 「有Claude Code、Codex、Cursor、GitHub Copilot实际使用经验」 |
| 微调/SFT/RLHF | 1/3 | 和生创新（偏算法向） |
| 网关治理（限流/熔断/成本统计） | 1/3 | 捷科智诚「TPM/RPM限流、熔断降级、SLO保障及成本统计」 |

### C. Agent 开发（专职岗，2 个完整 JD：立邦校招、无量火 + 列表约 15 条）

| 技能 | 出现频次 | 措辞示例 |
|---|---|---|
| 任务规划/工具调用/工作流编排 | 2/2 | 「任务流设计、执行逻辑编排与工具调用集成」 |
| 任务中断/异常恢复/状态持久化 | 2/2 | 无量火「任务调度、进程管理、状态机、任务中断、异常恢复和运行状态持久化」 |
| 安全沙盒/权限/审计 | 2/2 | 「用户授权、敏感操作确认、风险拦截、审计日志」 |
| LLM 基本原理 + API | 2/2 | 「了解LLM基本原理及常见应用模式」 |
| 语言要求 | 2/2 | 立邦「优先Python」；无量火「TypeScript/Rust/C++/Go或Python」——**Java 均非首选** |
| 可展示作品 | 1/2 | 立邦「有可展示的项目作品、代码仓库、Demo或技术输出者优先」 |

### 「源码」类要求统计

7 个完整 JD 中出现「熟悉XX框架源码 / 读过源码」字样的：**0 条**。JD 层面的深度措辞是「精通」「了解原理」（JVM 原理、集合、并发），框架源码要求只存在于面试八股环节，不写进 JD。

### 薪资基准（2026-08 实时）

- 校招 Agent 岗（立邦上海，26 应届）：10-20k·15薪
- Java 后端 1-3 年：12-28k（成都 12-25k、北京 18-28k/30-40k）
- AI 应用 2-5 年：15-30k 为主流带
- Agent 开发 3-5 年：15-40k（头部整车/金融 55-85k）

## 二、AgentFlow 技术栈命中/缺口清单

### 命中（JD 明确要、项目有）

| JD 要求 | AgentFlow 对应 | 命中强度 |
|---|---|---|
| Agent 编排/多智能体/任务流设计 | BSP 执行模型 + YAML DSL + 动态路由/回边，项目核心 | 强（差异化卖点） |
| RAG / Embeddings / 向量检索 | demo-rag + InMemoryVectorStore 余弦 top-k + 真实 LLM E2E | 强 |
| LLM API 集成 | DeepSeek 真实调用 + Spring AI 2.0 + LangChain4j 双适配器 | 强 |
| 工具调用 + 授权/审计/风险拦截 | @Tool + SafeToolExecutor + CallerToolAllowlist + 提交守卫 | 强 |
| 任务中断/异常恢复/状态持久化 | 两级 Checkpoint + Recovery Protocol + HITL 审批暂停恢复 | 强——与 Agent 岗 JD 措辞逐字对齐 |
| Spring AI（唯一点名 JD） | SpringAiAgentAdapter | 精准命中 |
| 消息队列 | Kafka dispatcher + 真 Kafka E2E IT + 幂等 | 中 |
| PostgreSQL/数据库 | Flyway 8 迁移 + 真 PG IT + 列加密 | 中（MySQL 口径需自补） |
| 可观测 | Micrometer + Grafana 6 面板 + Prometheus 实部署 | 中 |
| 测试/工程化/CI | 500+ 测试、JaCoCo 80% 门禁、12 模块 | 强（对应立邦「可展示代码仓库」优先项） |
| 成本统计/限流 | Token 记账 + 预算 + 422 提交拦截 | 中（对标捷科智诚 MaaS 网关 JD） |
| 前端 | React UI 6 Tab | 弱-中（JD 只要求「了解」） |

### 缺口（常见 JD 要求、项目没有）

1. **Spring Cloud/Alibaba 微服务体系（Nacos/Gateway/OpenFeign）**——Java 后端 JD 3/3 必考的最大缺口。AgentFlow 是单体多模块 + Kafka 解耦，有分布式思维但无 Spring Cloud 实物。
2. **Redis 实战深度**——3/3 JD 要求（分布式锁、缓存击穿/穿透）。项目仅在 docker-compose 里挂着 Redis，无可讲战绩。
3. **MyBatis**——2/3 要求，项目用 JdbcTemplate。
4. **Python**——AI 岗 3/3 提到，其中 2 条列为主语言。纯 Java 背景投 AI 应用岗时是显性短板。
5. **MCP**——2/3 AI 岗提到（加分项居多），项目无实现。
6. **Kubernetes**——仅 1 条扩展要求，项目只到 Docker compose，属小缺口。
7. **八股基础（JVM/并发/集合/MySQL 索引事务）**——JD 措辞为「精通Java基础」，项目完全不覆盖，需另行准备。

## 三、关键原始 JD 摘录

**1. 上海诺姆四达集团 — Java后端开发工程师（AI应用方向），13-26k，上海，2年以上本科**（对 AgentFlow 价值最高的一条）
> 「扩展要求：了解Spring AI等Java原生AI框架，有AI Agent/智能体相关实践经验者优先」「加分项：AI Agent工程经验：有AI Agent、AI工作流、RAG、MCP等项目实践经验」「AI应用开发经验：了解LLM API调用、RAG检索增强、Embeddings、向量检索、智能问答等AI应用相关概念」「熟练使用AI编程工具：有Claude Code、Codex、Cursor、GitHub Copilot等AI Coding工具的实际使用经验」
> https://www.liepin.com/job/1984256119.shtml

**2. 立邦中国 — AI Agent开发工程师（26年应届生），10-20k·15薪，上海，经验不限本科，学生可投**（唯一完整校招 JD）
> 「参与基于大语言模型的AI Agent应用设计与开发，包括任务流设计、执行逻辑编排与工具调用集成」「理解Agent、skills、工具调用、沙盒执行、工作流编排等基础概念」「优先：AI Agent / LLM应用开发项目经验；自动化工作流、任务执行链路或安全沙盒相关开发经验；大模型与业务系统/API集成项目经验；有可展示的项目作品、代码仓库、Demo或技术输出者优先」
> https://www.liepin.com/job/1982484575.shtml

**3. 和生创新（深圳智能硬件，年营收10亿） — AI应用开发工程师，18-25k，深圳，2-5年本科**
> 「设计和开发基于LLM的智能体（Agent）系统，具备任务规划、工具调用、记忆管理、对话控制等能力」「精通LLM相关技术，如Prompt工程、RAG」「具有Python或Java落地项目经验，具备多线程/异步并发开发能力」
> https://www.liepin.com/job/1982448241.shtml

**4. 捷科智诚（3600人金融IT服务商） — JAVA大模型应用开发工程师，15-30k，北京，经验不限本科，学生可投**
> 「负责企业级大模型MaaS网关的整体架构设计，建设统一的AI API服务入口，提供OpenAI等标准兼容API」「多租户隔离、鉴权、TPM/RPM限流、熔断降级、SLO保障及成本统计，确保万亿级Token流量的高可用」
> https://www.liepin.com/job/1984156161.shtml

**5. 无量火科技（上海端侧AI初创，天使轮数亿估值） — Agent开发工程师，15-40k·14薪，上海，3-5年本科**
> 「设计跨平台Agent Runtime，包括任务调度、进程管理、状态机、任务中断、异常恢复和运行状态持久化」「建设Agent工具调用、用户授权、敏感操作确认、风险拦截、审计日志和错误恢复机制」「熟悉TypeScript/Node.js、Rust、C++、Go或Python」（语言要求不含 Java）
> https://www.liepin.com/job/1983373211.shtml

**6. 兴业数金（兴业银行系金融科技） — Java后端开发，12-25k，成都，1-3年硕士**（传统 Java 后端基准样本）
> 「熟练掌握Java语言及常用框架（如Spring Boot、Spring Cloud等），具备扎实的数据结构与算法基础」「熟悉MySQL等关系型数据库」「了解分布式系统设计、微服务架构及常用中间件（如Redis、MQ等）」——无任何 AI 要求
> https://www.liepin.com/job/1984972931.shtml

**7. 萤火虫网络 — JAVA后端（BOSS直聘，广州，3-5年大专）**（BOSS直聘样本，技能标签）
> 标签：Java/Nginx/SpringCloud/Redis/Spring/SQL/Docker/Linux/MySQL/Mybatis——BOSS直聘未登录看不到任职要求全文
> https://www.zhipin.com/job_detail/c7d9a7560a65a1d91nV_0t-6EltV.html

## 四、直接判断：2026 届 Java 本科生带 AgentFlow 求职的市场匹配度

**总体：AI 应用工程方向匹配度高（约 70-80% 硬技能命中），传统 Java 后端方向匹配度中等（约 50-60%，缺 Spring Cloud + Redis 实战是硬伤）。项目本身的定位（后端工程 70% + Agent 30%）恰好卡在市场需求最强的位置上。**

1. **「Java + Agent 编排」是当前真实存在且增长的缝隙市场**。Agent 开发岗大量存在（猎聘单关键词 20+ 条在招，15-85k），且 JD 核心措辞（任务规划、工具调用、工作流编排、任务中断、异常恢复、状态持久化、安全沙盒、审计日志）与 AgentFlow 的 Checkpoint/Recovery/HITL/工具授权实现**逐字级对齐**——这不是巧合式命中，是项目按工程痛点建的。面试官若懂行，这个项目能撑住深挖。

2. **Spring AI 点名出现的频率低但方向正确**。7 个 JD 中仅诺姆四达一条点名「Spring AI + Java 原生 AI 框架」，但该条同时把「AI Agent 工作流/RAG/MCP 项目经验」列为加分项——AgentFlow 是这条 JD 的教科书式答案。更普遍的现实是：AI 应用岗 3/3 把 Python 列为主语言或优先语言，纯 Java 投 AI 岗会吃亏，需要用「Java 生态 AI 工程化是差异化（对标 LangChain4j/Spring AI 生态位）」的叙事对冲。

3. **投传统 Java 后端岗时项目只能算半张牌**。Spring Cloud/Alibaba 是 Java 后端 JD 无一例外的标配，AgentFlow 没有；Redis 实战（分布式锁、缓存问题）没有。这两项加上 MyBatis，是简历过筛环节最常见的硬过滤词。AgentFlow 在这类岗位的价值是「证明工程素养和复杂系统设计能力」，不能替代技术栈关键词匹配。

4. **校招通道的实证**：立邦中国 26 应届 Agent 岗明确写「AI Agent/LLM应用开发项目经验」「可展示的代码仓库、Demo」优先——AgentFlow 加 GitHub 门面直接满足全部优先项。捷科智诚「经验不限/学生可投」的 LLM 网关岗也证明应届通道存在。但注意：Agent 岗总量上「经验不限/应届」占比低（约 15 个 Agent 列表里 3-4 条），大头仍是 3-5 年档，应届生需要靠项目强度跨越经验门槛。

5. **最大风险不是技术栈而是叙事**：JD 层面 0/7 出现「框架源码」要求，说明「读过源码」不是筛人标准；但 JD 普遍要求「精通Java基础（JVM/并发/集合）」，这部分靠项目覆盖不了，八股准备不可省。项目能拿到面试，八股决定能不能过。
