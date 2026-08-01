# 模块 10 · 多 Agent（spike 决策 → lead + subagents）— 版本 C

> greenfield。🟢复刻两种引擎的官方样例 → 🟡改造成本项目 subagent → 🔴自研护栏与选型。**这是 agent 岗面试的重头戏。**

## 学完本模块你能做到的 3 件事
1. 亲手对比 DeepAgents 与 LangGraph 原生 subgraph，用"可 checkpoint 恢复性 + 代码量 + API 稳定性"选定内层引擎并说清为什么。
2. 建出 lead + metrics/logs/trace 三个**最小权限** subagent，各只持本域工具。
3. 把"lead 不得批量读证据"的上下文护栏做成**工具层硬约束**（而非仅写进 prompt），让上下文瘦身可靠。

## 本模块交付物清单（文件路径级）
- `docs/dependency-baseline.md`（追加：内层引擎 spike 结论）
- `src/.../agents/subagents.py`（三 subagent 定义）
- `src/.../agents/lead.py`（lead 编排）
- `src/.../graph/nodes/investigate.py`（改：subagents_enabled 分发）

---

## 任务 T10.1 · spike：DeepAgents vs LangGraph 原生 subgraph

**id**：T10.1 ｜ **所属模块**：M10 ｜ **优先级**：P0

**一句话目标**：两种引擎各跑一个"lead 派 1 个 metrics subagent 取证"的最小样例，用实测选型。

**前置知识（≤2 新概念）**
- **多 Agent / subagent 委派**：一个 lead 把子任务派给专职 subagent，各自独立上下文取证后回摘要。*Java 类比*：主管把活派给专职下属，下属只回结论不回一堆原始材料。📖 https://github.com/langchain-ai/deepagents （DeepAgents，需核实锚点）· video-3 P3-6「Multi-Agent vs Skills 架构·代码实战」
- **内层可 checkpoint 恢复性**：审批挂起/崩溃后 resume，内层取证进度能否不丢，是选型关键 [R7]。*Java 类比*：子任务的中间状态能否随主流程一起持久化恢复。📖 https://docs.langchain.com/oss/python/langgraph （subgraphs/persistence，需核实锚点）· video-4 P29-31「AsyncSubAgent 解析」

### 🟢 第 1 段 · 复刻
- **读什么**：DeepAgents README 的 subagent 委派样例；LangGraph 原生 subgraph 官方样例（`langgraph-supervisor` 也一并瞄一眼作参照）。
- **跑什么**：两者各照样例跑一个最小"lead+1 subagent"，都能跑通。

### 🟡 第 2 段 · 改造
- 两个样例都改成"lead 派 1 个 metrics subagent 调真实 query_metrics 取证一次"，跑通并对比：代码量、上手难度。

### 🔴 第 3 段 · 自研
- **自己设计**一个恢复性测试：让 subagent 取证到一半 kill 进程重启，看两种引擎哪个能 resume 不丢内层进度；把"代码量 / 恢复性 / API 稳定性 / 无视频覆盖度"四项结论写进 `docs/dependency-baseline.md`，**锁定内层引擎**。

**落笔顺序**
1. 复刻：两引擎最小样例各跑通。
2. 改造：都接真实 query_metrics 取证。
3. 发一次请求：跑到一半 kill 进程。
4. 自研恢复性测试，对比 resume 能力。
5. 写结论、锁引擎。

**关键提示（≤3）**
- 结论要落文档并给出"为什么"，这就是面试被问"为什么用 X 不用 Y"的答案。
- DeepAgents 无视频覆盖，靠官方文档；langgraph 原生有 video-2 P58-64 兜底。
- 别纠结完美，能支撑选型即可，时间盒半天。

**卡住降级路径（30 分钟没思路）**
- 样例跑不通：先各自单跑官方最小 demo，排除是本项目接线问题。
- 视频：video-4 P16「Multi-Agent 解析」+ P29-31「AsyncSubAgent」建立多 Agent 直觉。
- 问 AI：「DeepAgents 的 subagent 内层状态，在外层 LangGraph checkpointer 下崩溃 resume 后会不会丢？给我一个验证这件事的最小实验设计。」

**真实 HTTP 验收（含异常路径）**
```bash
# 两引擎各驱动一次真实取证
uv run python scripts/spike_deepagents.py   # lead 派 metrics subagent 调真实 query_metrics
uv run python scripts/spike_subgraph.py
# 恢复性：spike 跑到 subagent 取证时 kill，重启看能否 resume
# 结论写入 docs/dependency-baseline.md
```
判定特征：两引擎都能真实取证；恢复性差异被实测记录；baseline 有明确选型 + 理由。依赖真实服务：M2 网关、Prometheus、investigate 模型。

**完成判定**
- [ ] 两引擎最小样例各跑通（复刻段）
- [ ] 都接真实 query_metrics 取证成功
- [ ] 恢复性实测对比完成
- [ ] `dependency-baseline.md` 有选型结论 + 四项理由

---

## 任务 T10.2 · lead + 三 subagent 上线（替换 investigate 主实现）

**id**：T10.2 ｜ **所属模块**：M10 ｜ **优先级**：P0

**一句话目标**：用选定引擎建 lead + metrics/logs/trace 三 subagent，接管 investigate 节点。

**前置知识（≤2 新概念）**
- **最小权限 subagent**：每个 subagent 只持本域工具（metrics-investigator 只有 query_metrics + record_evidence）。*Java 类比*：按角色授权，专员只拿自己该用的接口权限。📖（复用 T10.1 选定引擎）
- **offload 上下文护栏做成工具层硬约束 [R11]**：lead 只暴露"读单个 evidence_id 摘要"工具，**不提供批量读目录工具**——而非仅在 prompt 里写"别批量读"。*Java 类比*：靠接口不暴露来禁止，而非靠注释请求别调。📖（自研）

### 🟢 第 1 段 · 复刻
- **读什么**：T10.1 选定引擎的 subagent 定义样例。
- **跑什么**：照样例定义 1 个 subagent 跑通。

### 🟡 第 2 段 · 改造
- 定义 `metrics/logs/trace` 三 subagent（各自最小工具集 + record_evidence，`X-Agent-Role` 按 subagent 注入）；`lead.py` 编排节奏：首轮并行广撒 → 汇总更新 hypotheses → 定向追查 → 写 report_draft（结论带 [EV-*]）。

### 🔴 第 3 段 · 自研
- **从空文件**写 offload 硬约束：lead 只暴露"读单个 evidence 摘要"工具，无批量读；内层关键中间态（hypotheses/evidence 索引）落回 IncidentState 或 subgraph checkpoint 保证 resume [R7]；`investigate.py` 按 `subagents_enabled` 分发（True→lead，False→M7 simple）。

**落笔顺序**
1. 复刻选定引擎的 subagent 样例。
2. 改造出三 subagent + lead 编排。
3. 发一次请求：注入故障，跑 lead 多 Agent 排查。
4. 自研 offload 硬约束 + investigate 分发开关。
5. 验证审批挂起 kill-resume 内层不丢（R7）。

**关键提示（≤3）**
- 三 subagent 最小权限，别给它们本域外的工具。
- offload 靠"不暴露批量读工具"硬约束，不靠 prompt 求它。
- 与 M7 simple 共享 EvidenceStore 与回流逻辑，靠开关切（A2 消融）。

**卡住降级路径（30 分钟没思路）**
- lead 不派活/乱派：检查 lead instructions 的节奏描述和 subagent 的 description。
- 视频：video-4 P22-23「Context-Engineering」讲上下文瘦身思路。
- 问 AI：「我想让 lead Agent 只能读单条证据摘要、不能批量读证据目录，怎么通过'不暴露批量工具'来硬性约束，而不是在 prompt 里请求它？」

**真实 HTTP 验收（含异常路径）**
```bash
curl -XPOST localhost:8000/webhook/alertmanager -d @some-fault-alert.json
ls runs/incidents/inc-*/   # plan.md/hypotheses.md/evidence/*/report_draft.md 齐全
# 分层 trace（LangSmith 或日志）可见 lead→subagent
# 异常路径：审批挂起时 kill 进程重启 → resume 后内层取证进度不丢（验证 R7）
```
判定特征：多 Agent 完整排查、分层 trace 可见；kill-resume 内层不丢。依赖真实服务：M2 网关、Prometheus/Loki/Tempo、investigate 模型。

**完成判定**
- [ ] 选定引擎 subagent 样例复现（复刻段）
- [ ] 三 subagent 最小权限、role header 正确
- [ ] offload 靠工具层硬约束（无批量读工具）
- [ ] `subagents_enabled` 切换 lead / simple（A2）
- [ ] 审批挂起 kill-resume 内层进度不丢

---
*版本 C 特征：多 Agent 全靠"复刻两引擎样例 → 实测选型 → 改造成最小权限 subagent → 自研护栏硬约束"，把面试高频的多 Agent 从对比实验一路落到可讲清的设计。*
