# 就业市场调研档案（2026-08-26）

> 背景：为「AgentFlow 写上简历」做的三线调研 + 综合成稿 + /asu 亮点酥化。调研方法：3 个并行调研线（招聘平台 JD / 牛客一手帖 / GitHub API 核实），本地素材来自 `docs/developer-notes/00-interview-arsenal.md` 与 `docs/learning/Agentflow-code/` 学习档案 + `docs/business/` 业务流程文档。

## 文档导览

| 文档 | 内容 | 核实状态 |
|---|---|---|
| [00-agentflow-highlights-asu.md](00-agentflow-highlights-asu.md) | /asu 酥化成稿：8 条主 bullet（引擎/恢复/HITL/Kafka/适配器/安全/质量/UI）+ 两版 HR 开场白 + 主张审计表 | 与 claim-ledger 一一对应（2026-08-29，UI 主张已补入） |
| [01-job-market-jd-analysis.md](01-job-market-jd-analysis.md) | 7 份完整 JD 的技能要求汇总、AgentFlow 命中/缺口清单、薪资基准、投递方向匹配度判断 | 猎聘一手（2026-08-26 实时在招） |
| [02-resume-writing-guide.md](02-resume-writing-guide.md) | 过筛写法 11 条、雷区 10 条、AI 时代可信度分层、造轮子定位、面试官原话摘录 | 牛客一手帖（含阿酥事件后验真升级） |
| [03-undergrad-authenticity-check.md](03-undergrad-authenticity-check.md) | Spring AI/LangChain4j/Spring AI Alibaba 团队真相、学生先例、AI 辅助时代结论、验真手段 | GitHub API 现场核实；学生先例/英文舆论为模型知识（已标注） |
| [04-resume-draft-and-strategy.md](04-resume-draft-and-strategy.md) | 简历成稿模板（AI 岗版/后端版）、AI 辅助口径、投递策略、面试弹药衔接 | 基于上述调研的综合成稿 |
| `docs/resumes/`（A-agent.html / B-backend.html） | 可编辑 HTML 简历成稿：A=AI/Agent 岗（AgentFlow 主项目），B=Java 后端岗（ToyRush 前置） | 2026-08-29 落地；qa-A.png / qa-B.png 为 Playwright 整页截图 QA |

## 核心结论速览

1. **命中度**：AI 应用/Agent 岗 70-80%（JD 措辞与实现逐字对齐）；传统 Java 后端 50-60%（缺 Spring Cloud/Redis，由 ToyRush 承接）
2. **可信度**：AgentFlow 量级 ≈ Spring AI 主代码 9%，对标「框架核心子集」定位合理；LangChain4j 本身就是个人起手项目——「异常于旧基线，正常于新基线」
3. **写法**：每条 bullet「问题→方案→结果」闭环；写决策与证据，不写功能与规模；GitHub 链接 + 测试数据放第一屏；主动讲踩坑
4. **环境**：2026-08 阿酥事件后面试验真升级——项目细节/取舍/失败经历权重上升，对「工程闭环 + 173 commits 可辩护」的项目是利好
5. **缺口**：Python（叙事对冲）、MCP（建议补最小接入）、八股（另行准备，项目覆盖不了）

## 成稿 QA 记录（2026-08-29）

- 照片路径修复：两版 HTML 原引用 `../fictional-resume-photo.png`（解析到 `docs/` 根目录，裂图），已改为 `../career-research/resume/fictional-resume-photo.png`（该占位图实际所在目录）；交付用户前提醒替换真实证件照。
- B 版 ToyRush「才冲」乱码已按用户原 PDF（`docs/王永盛-27届计科专业.pdf`）校准为「Kafka 削峰下单流量；Spring Task 定时任务处理超时未支付订单」——不引入 PDF 之外的编造细节。
- QA 方法：`docs/resumes/qa.py`（Playwright 无头 Chromium 整页截图 + resume-page 数量/字符数断言），输出 `qa-A.png` / `qa-B.png`。
