# 导学-ReportAutomation（货代报表自动化 · MYM/XTS 报告平台）

> 素材来源：`C:\Users\Yusheng\intern-diary\CodeLearning\MYM Report\` + `CodeLearning\XTS Report\`（代码学习档案，分析基线 `report_automation@d2edb7b`）+ `Business\MYM Report\`、`Business\XTS Report\`（业务文档）+ 实习日记（`实习记录-2026-06-29_至_07-14.md` / `cicd-实习日记.md` / `monitor-dashboard-实习日记.md` / `oocl-cloudflare-turnstile-bypass.md` / `N8N_SHAREPOINT_INTEGRATION.md`）
> 整理时间：2026-08-31 ｜ 配套：`面经-ReportAutomation.md`（简历 bullet + 18 主问题 STAR 口播 + 源码证据索引）+ `05-internship-report-automation.md`（/asu 成稿）

## 1. 前置知识（面试高频标注）

| 知识点 | 为何需要 | 在本项目中的位置 | 高频度 |
|---|---|---|---|
| Playwright 等待机制体系 | 简历第一 bullet 的底层知识：auto-waiting / expect_response / wait_for_selector / 行数稳定判定，142 处盲等优化的理论支撑 | 各船司爬虫 `track_container.py` | ★★★ |
| 反爬对抗组合拳（CF/Turnstile/验证码） | 面试最有故事的模块：真 Chrome channel + 持久 profile + API 预热 + OpenCV 视觉定位 + Cookie 导出注入 | `oocl/cf_bypass.py`、`zim/`、`whl/browser.py`、`emc/captcha.py` | ★★★ |
| 子进程异步任务模型 | 长任务（5-30 分钟）Web 承载的主打方案：Popen + 日志文件 + poll() 崩溃检测 | `app/api.py` 上传端点 + `run_excel.py` | ★★★ |
| 数据标准化（异构→统一契约） | 14 家船司数据收敛的核心模式：每家一个适配器 + 共享 mapper，12 种日期正则 | `modules/standardizer/mapper.py` | ★★★ |
| 缓存设计与防毒化 | batch_key 分桶 + 重试轮只存非空——「坏数据不覆盖好缓存」的防御性设计 | `shipment_query.py#fetch_shipment_results` | ★★☆ |
| 纯数据层拆分（import 约束边界） | shipment_query 不碰 openpyxl——用 import 方向做机器可检查的分层 | `modules/excel_local_deal/src/shipment_query.py` | ★★☆ |
| 配置驱动（yml 报告适配） | 新报告零代码接入；能力放代码、行为放配置的分离纪律 | `run_shipment.yml` + `carriers.yaml` | ★★☆ |
| 12-Factor 配置外置 | 离线部署一根因（config 写死 = 两套包）的正解 | `config.py` → 外部 config.yaml | ★★☆ |
| OAuth2 委托权限 vs 应用权限 | n8n × SharePoint 打通的关键前提：无管理员走 Delegated + offline_access | `N8N_SHAREPOINT_INTEGRATION.md` | ★☆☆ |
| SQLite 迁移套路 | 无 DROP COLUMN 的四步迁移（建新-导数-删旧-改名）+ INSERT OR IGNORE 幂等 | `migrations/001_feedback_refactor.sql`（辅助参与） | ★☆☆ |
| DCSA T&T 标准 | 「弃爬改 API」终局解的行业标准：8 家船司对齐统一端点 | API 调研记录 | ★☆☆ |

## 2. 重点亮点与学习顺序（先看这个）

| 亮点标题 | 为什么重要 | 通用技术关键词 | 先看哪些文件/档案 | 建议学习顺序 |
|---|---|---|---|---|
| ① 爬虫等待机制重构（142 处盲等清理） | 🟢 本人主导的最大规模改动：从「靠超时兜底」到「可判定成功失败」，有完整的方法论（决策矩阵） | 条件驱动等待、expect_response、or_() 合并、成功信号 | 实习记录 §1.2（45 条记录）→ `CodeLearning\MYM Report\implementation-walkthroughs\carrier-crawler-overview.md` | 1 |
| ② 反爬攻坚四案例 | 🟢 本人主导：OOCL OpenCV 换范式 / ZIM 真 Chrome+预热+XHR / WHL Cookie 导出注入 / EMC 视觉大模型——「没有银弹，逐家研究」的完整样本 | Cloudflare、Turnstile、模板匹配、cookie 语义、反指纹 | `oocl-cloudflare-turnstile-bypass.md` + 实习记录 §1.4 + `learning-summaries/carrier-antibot-and-api.md` | 2 |
| ③ 子进程长任务模型 | 🟢 本人主导（req-cicd-003/005）：Web API 承载 5-30 分钟任务的完整工程化——进程隔离/文件状态/进度协议/产物治理 | Popen、CREATE_NEW_PROCESS_GROUP、stdout 协议、幂等清理 | `cicd-实习日记.md` 日记二/三 | 3 |
| ④ 异构数据标准化管道 | 系统最精巧的设计（理解深度锚点）：current_flag 纯计算派生 + mockLocation 语义哨标 + 12 种日期归一 | 适配器模式、派生态、语义化占位符 | `implementation-walkthroughs/standardizer-current-flag.md` + `shipment-query.md` | 4 |
| ⑤ 离线部署工程化 | 🟢 本人主导：零网络 Windows 环境的完整部署策略 + 双重前缀 404 排障案例 | wheelhouse、12-Factor、构建产物 diff 排障 | `cicd-实习日记.md` 日记一/四 + 实习记录 §1.6 | 5 |
| ⑥ n8n × SharePoint 打通 | 🟢 本人主导（部门首次）：OAuth2 委托权限 + REST/内置节点混合路线 + audience 401 隐蔽坑 | OAuth2、audience/scope、API 受众 | `N8N_SHAREPOINT_INTEGRATION.md`（10 节踩坑指南） | 6 |
| ⑦ 缓存防毒化与紫/红重试 | 🟡 辅助参与（XTS 接入）：force_rescrape 只存非空——防御性缓存设计范本 | 缓存隔离、重试语义、能力/行为分离 | `CodeLearning\XTS Report\technical-decisions\search-type-and-retry.md`（XTS 版） | 7 |
| ⑧ 数据质量闭环与监控看板 | 🟡 辅助参与（godtree 主导）：query_log 观测面 + user_feedback 人工纠偏 + parse_error 动态派生 | 观测面解耦、派生态、反馈闭环 | `implementation-walkthroughs/monitoring.md` + `monitor-dashboard-实习日记.md` | 8 |

## 3. 必备知识点 checklist

- [ ] 能说出「数据加载机制 → 等待策略」决策矩阵四象限（API/整页导航/SPA/验证码）各自配什么等待
- [ ] 能解释 wait_for_timeout 是反模式的根因（不反映页面真实状态 + 网络波动直接崩）
- [ ] 能手推 OOCL OpenCV 方案链路：全页截图 → 匹配 Turnstile 框 → 裁剪 ROI → 匹配 checkbox → 绝对坐标 → 人类化点击，以及三层回退为什么这么排
- [ ] 能解释 ZIM「requests 直调 403、浏览器 XHR 有返回」的根因（Origin/Referer + cf_clearance 校验）
- [ ] 能解释 WHL cookie 注入的 host-only 陷阱（无前导点 domain 要用 url= 注入）
- [ ] 能画出子进程任务模型的完整时序（上传→Popen→日志→轮询→poll() 判终→下载）
- [ ] 能解释为什么 CREATE_NEW_PROCESS_GROUP 在 Windows 上必要（reload 的 Ctrl+C 传播）
- [ ] 能解释 current_flag 为什么用「日期区间计算」而非读网站 flag 列（普适语义适配所有船司）
- [ ] 能解释 mockLocation 哨标的两处特判（current_flag 并列优先 + calc 到港放行）及其语义
- [ ] 能讲缓存防毒化两道闸：batch_key 分桶隔离 + 重试轮只存非空
- [ ] 能说出纯数据层的 import 约束（不 import openpyxl）带来的两个收益（机器可查边界 + 可独立单测）
- [ ] 能讲配置外置如何消灭「测试/生产两套包」（12-Factor：一份包 N 份配置）
- [ ] 能区分委托权限与应用权限（无管理员 → Delegated + offline_access 续期）
- [ ] 能诚实分层：系统是接手的十几万行存量，我主导四块半（爬虫优化/反爬/长任务/部署/n8n），XTS/监控辅助参与

## 4. 推荐阅读（结合素材档案）

| 主题 | 通用技术点 | 建议阅读位置（相对素材根） | 预计时间 | 读完能回答什么 |
|---|---|---|---|---|
| 系统全景 | 进程边界/入口/外部依赖 | `CodeLearning\MYM Report\system-overview.md` | 20min | 系统几层、子进程边界在哪、SQLite/PG 各管什么 |
| 爬虫全景 | 14 家差异对照表 | `CodeLearning\MYM Report\implementation-walkthroughs\carrier-crawler-overview.md` | 30min | 三类入口（API/页面/验证码）与反爬分级 |
| 等待策略方法论 | 条件等待体系 | `实习记录-2026-06-29_至_07-14.md` §1.2（45 条） | 40min | 每种等待手段的适用场景与反面案例 |
| OOCL 专案 | 视觉定位方案 | `oocl-cloudflare-turnstile-bypass.md` | 30min | OpenCV 两步匹配 + 三层回退全链路 |
| 数据获取链路 | 缓存+爬取+计算 | `CodeLearning\MYM Report\implementation-walkthroughs\shipment-query.md` | 30min | fetch 总入口的完整调用链与标色规则 |
| 标准化内核 | current_flag/日期归一 | `CodeLearning\MYM Report\implementation-walkthroughs\standardizer-current-flag.md` | 30min | 异构→统一的技术内核 |
| 长任务工程化 | 子进程模型 | `cicd-实习日记.md` 日记二/三 | 30min | 任务生命周期管理与产物治理 |
| XTS 接入 | search_type/重试 | `CodeLearning\XTS Report\technical-decisions\search-type-and-retry.md` + `pure-data-layer-shipment-query.md` | 30min | 能力/行为分离与缓存防毒化 |
| 监控闭环 | 观测面+反馈 | `CodeLearning\MYM Report\implementation-walkthroughs\monitoring.md` | 20min | parse_error 派生态与反馈唯一键 |
| n8n 专案 | OAuth2+audience | `N8N_SHAREPOINT_INTEGRATION.md` | 30min | 委托权限路线与 REST/内置节点混合的依据 |
| 学习总结 | 第一人称误解修正史 | `CodeLearning\MYM Report\learning-summaries\carrier-antibot-and-api.md` | 15min | 「我以为…实际…」面试素材（zim 限流归因/mockLocation 失效疑点/配置驱动隐性成本） |

## 5. 自学提醒

若某文件或原理看不懂，请继续追问 AI；本技能负责给学习路径与题目，不提供逐行讲解。

## 6. 项目技术定位

**数据工程 + 爬虫工程偏后端**（Python 栈：FastAPI 编排 + Playwright 抓取 + pandas/openpyxl 流水线 + SQLite 存储）。依据：核心复杂度在「与不可控外部系统打交道」（14 家异构网站的反爬/格式差异）与「长任务工程化」（进程模型/状态存储/产物治理），Web 层本身是薄编排层。

## 7. 核心原理解析

1. **条件驱动等待：为什么 wait_for_timeout 是反模式**
   问题 → 固定盲等的时间要么太短抓不到、要么太长浪费；它不反映页面真实状态，网络波动超出设定直接崩，且失败被 timeout 兜底层层传染。
   机制 → 五种条件等待按场景选用：Locator 自带 auto-waiting（多数 sleep 纯属多余）、wait_for_selector/load_state 等元素/状态、expect_response 拦后端 API（SPA 首选，直接取 JSON）、wait_for_function 等自定义条件、AJAX 表格判行数稳定 + 特征词。
   落点 → 实习记录 §1.2 共 45 条记录；142 处盲等（OOCL 45/EMC 21/COSCO 20）逐家替换。学到边界：抓取函数返回值必须反映真实成功/失败——「永远 return True」会让上层被迫 timeout 兜底。

2. **多候选选择器合并：性能黑洞的定位与修复**
   问题 → EMC 11/10/13 个候选选择器逐个 `is_visible(timeout=2s)` 串行探测，每个不存在的都等满 2s，最坏 22s——慢的根不是盲等本身。
   机制 → `locator.or_()` 把多候选合并为一个 Locator，只 `wait_for()` 一次（8s 超时）。
   落点 → `_first_match(page, selectors, timeout)` 封装。学到方法：优化前先定位「时间都花在哪」——22s 是串行探测的数学和，不是单点慢。

3. **OOCL OpenCV 视觉定位：DOM 不可达时换范式**
   问题 → Turnstile checkbox 嵌套跨域 iframe（challenges.cloudflare.com）+ 多层 Shadow DOM，`contentDocument` 与 `querySelector` 双双失效；此前 stealth 反检测方案稳定性不够。
   机制 → 纯视觉定位不碰 DOM：全页截图 → 模板匹配 Turnstile 框（亮/暗双模板，置信度 ≥0.6）→ 裁剪 ROI → 匹配 checkbox（≥0.5）→ 计算绝对坐标 → 人类化点击；OpenCV 可选依赖（try import），失败按「frame_locator → frame 遍历 → shadowRoot 递归穿透」三层回退。
   落点 → `cf_bypass.py`，commit `93673fa`。学到取舍：跨域边界是浏览器安全模型不是 bug，「在像素层找答案」比「凿穿隔离」更稳。

4. **ZIM 三件套：真 Chrome + API 预热 + page.evaluate XHR**
   问题 → requests 直调 apigw 403，浏览器控制台 XHR 却每次有返回。
   机制 → 根因是 apigw 校验 Origin/Referer + `cf_clearance` cookie：① `channel="chrome"` 启动本机真 Chrome（UA 与真 Chrome 天然一致，去写死 UA）；② 批量调 API 前先对 apigw 预热一次，让 CF 质询通过、cf_clearance 落进 cookie jar；③ `page.evaluate` 跑 XHR——请求从页面上下文发出，origin 自动正确。
   落点 → `modules/web_scraping/src/zim/`。学到归因纪律：zim 的 429/403 有两道墙——CF 在首页域、APIM 限流在 API 域，两码事；先归因再设计退避，重试过量反而触发限流。

5. **子进程长任务模型：文件系统胜过内存状态**
   问题 → Web API 不能同步跑 5-30 分钟流水线；用线程/内存字典记录任务，服务重启全丢。
   机制 → subprocess.Popen 启动 run_excel.py，stdout 逐行写 `output/{result_id}.log`；前端 2s 轮询读日志；`poll() is None` 判 running（正常退出与崩溃统一为 running=false）；`CREATE_NEW_PROCESS_GROUP` 防 uvicorn reload 的 Ctrl+C 传播杀子进程；历史记录扫磁盘推导状态——服务重启不丢、多实例自然共享。
   落点 → `app/api.py` + `run_excel.py`（commit 6817386 起系列）。学到取舍：stdout 打 PROGRESS 标记 + 文件轮询 = 零依赖任务通信，比 WebSocket/SSE 在离线环境简单可靠。

6. **异构数据标准化：统一契约 + 纯计算派生态**
   问题 → 14 家网站页面结构、日期格式（近 12 种）、事件文案全不同，下游不应感知任何一家差异。
   机制 → 每家一个小适配器产出 raw dict，`carriers.yaml` 的 column_mapping/type_mapping 翻译，`_format_date` 12 种正则统一 DD-MON-YYYY（月份缩写硬编码字典避 Windows 中文 locale 坑）；current_flag 不读网站 flag 列，用「date≤today 的最大记录」纯计算（并列时优先 mockLocation 哨标行）；mockLocation 是语义化占位符——「这条到港来自权威字段，别用地点相似度卡我」，在 current_flag 与 calc_four_type_date 两处被下游理解。
   落点 → `modules/standardizer/mapper.py` + `carriers.yaml`。学到设计观：能用推断计算的领域状态尽量别依赖供应商给不给标记。

7. **缓存防毒化：隔离 + 只存非空**
   问题 → 重试轮若把空结果/坏结果写进缓存，会污染本来正确的好缓存。
   机制 → 两道闸：batch_key 分桶隔离（不同批次互不覆盖）+ 重试轮 `force_rescrape=True` 时 `batch_save` 只保存非空结果。
   落点 → `shipment_query.py#fetch_shipment_results`。学到范式：写路径的防御性设计（「什么不写」比「写什么」更重要）。

8. **纯数据层：用 import 约束做机器可检查的边界**
   问题 → 「carrier 解析+缓存+爬取+计算」与「Excel 标色写入」耦合，数据逻辑无法独立测试。
   机制 → shipment_query.py 不 import openpyxl、不碰 workbook，只对「行 dict → 结果 dict」负责；入参 field_mapping 解耦 Excel 表头与数据层字段；Web 手动查询与流水线复用同一爬取入口。
   落点 → `shipment_query.py`（req-shipment-004 重构）。学到边界观：光「不 import xxx」一句约定就让分层可被机器检查，迫使 Excel 逻辑只能留在上层。

## 8. 关键设计决策

| 决策 | 备选 | 取舍 | 风险 | 验证 |
|---|---|---|---|---|
| 条件等待全面替换盲等（142 处） | 保持 timeout 兜底 + 加长等待 | 可判定成功失败；代价是逐家分析数据加载机制 | 网站改版需重新适配 | 实习记录 45 条逐家记录 + 决策矩阵 |
| OOCL 用 OpenCV 视觉定位 | 凿穿 iframe/Shadow DOM、patchright 反检测补丁 | 跨域边界不可凿，像素层找答案；参考 Kameleo 再适配（亮暗双模板/三层回退是站点适配增量） | 网站改版/主题变化需换模板 | commit 93673fa 落地 + 实跑通过 |
| ZIM 真 Chrome + 预热 + XHR | requests 直调、patchright | 借页面 origin 绕 CORS；代价是依赖浏览器会话 | 本机 Chrome 冲突（新实例被吸进现有窗口） | 实跑稳定抓取 |
| 子进程 + 日志文件（非线程/Celery） | 线程 + 内存字典、引入任务队列 | 进程隔离崩溃不影响主服务、文件状态重启不丢、零依赖；代价是无分布式能力 | Windows 单机形态（reload 泄漏子进程待改进） | poll() 崩溃检测 + mock 时间戳验证清理 |
| dist 产物提交 Git（离线部署） | 服务器上 npm install、制品库 | 离线环境最可靠；违背「build 不进仓库」通则但 hash 文件名天然缓存失效 | 仓库体积膨胀 | 部署实跑 + /legacy 回退兜底 |
| 配置外置（12-Factor） | 每环境一个包 | 一份包 N 份配置；根因是 config.py 写死路径 | 无（纯收益） | AST/UAT 多环境部署验证 |
| 委托权限（Delegated）打通 SharePoint | 应用权限（需管理员同意） | 普通用户自同意即可；代价是 token 代表真实用户、需 refresh 续期 | 租户锁用户自同意 → 走管理员审批 | POC 下载+上传双向验证 |
| REST + 内置节点混合路线（n8n） | 全 REST 或全内置节点 | 下载走 SharePoint REST（绕内置节点级联选择器）、上传走内置节点（支持动态文件名）——按「动态文件名表达式 vs 固定 GUID」能力差异选路线 | 两路线认证 audience 不一致（Graph vs SharePoint REST） | POC 双向验证 + 10 节踩坑指南沉淀 |

## 9. 量化与验证（含待测，建议）

- **等待优化效果**：定性口径有据（EMC 多候选最坏 22s → 单次等待 8s 内；CMA 加延时后不再封 IP）——无精确压测数字，面试口径「22s→8s 内」有逐条日记支撑，不编百分比。
- **142 处 / 11 家**：有逐条记录（OOCL 45/EMC 21/COSCO 20 最多）——报数字时可展开构成。
- **建议补的测量**：① 各船司条件等待替换前后的单次查询耗时分布（query_log 有数据基础）；② 缓存命中率（batch_load 命中/未命中比）；③ 紫/红重试的实际触发率与修复成功率（验证重试是否「纯准备」路径——cma 加 BL 后注释已过时）。
- **已知缺口（主动讲）**：① zim 合成到港行 mockLocation 放在 placeTo 但 column_mapping 读 placeFromDesc——哨标可能未真正命中（Q8 待实跑验证）；② XTS 配置与测试漂移（4 个失败测试——配置化的隐性维护成本活例）；③ 无 CI 流水线/无 Docker（离线环境限制，如实答）。
