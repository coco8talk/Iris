# 模块 10 · 多 Agent（spike 决策 → lead + subagents）— 版本 C

> 编排理念：🟡在本项目真实代码上改造（把 M7 的单 Agent 节点做成可切换的两种内层引擎实现，用真实事故实测选型）→ 🔴自研最小权限分工与 offload 硬约束。**这是 agent 岗面试的重头戏。**

**本模块的能力边界（先对齐）**
- **入口是 M5 的 FastAPI，端口 8000**；checkpointer（M5 T5.2）已就位，所以"kill 进程重启后内层进度丢不丢"是**可以真实测出来**的，不用靠推理。
- **7 个工具已在 M7 全部接入**，且 `tools/registry.py` 已经把它们分成了模板组和 raw 组——本模块的最小权限分工直接建在这个分组上，不需要再接任何新工具。
- **不写脱离项目的 spike 脚本**：两个候选引擎都实现成 `nodes/investigate.py` 的**可切换实现**，用真实 webhook 驱动、真实网关取证。选型结束后**删掉落选的那一份**，胜出的那份就是正式代码。
- **`EvidenceStore`（M7）被三个 subagent 共用**：编号仍全局按 kind 递增，`agent_role` 字段区分是谁取的证。

## 学完本模块你能做到的 3 件事
1. 亲手把 DeepAgents 与 LangGraph 原生 subgraph 各实现成本项目 investigate 节点的一种实现，用"可 checkpoint 恢复性 + 代码量 + API 稳定性"实测选型并说清为什么。
2. 建出 lead + metrics/logs/trace 三个**最小权限** subagent，各只持本域工具。
3. 把"lead 不得批量读证据"的上下文护栏做成**工具层硬约束**（而非仅写进 prompt），让上下文瘦身可靠。

## 本模块交付物清单（文件路径级）
- `docs/dependency-baseline.md`（**追加**：内层引擎 spike 结论 + 四项理由）
- `iris-agent-platform/src/agent/subagents.py`（**新建**：三个 subagent 定义 + 各自工具集）
- `iris-agent-platform/src/agent/lead.py`（**新建**：lead 编排 + offload 受限工具）
- `iris-agent-platform/src/agent/nodes/investigate.py`（**改造**：按 `subagents_enabled` 在 lead / M7 单 Agent 之间分发）
- `iris-agent-platform/src/agent/config.py`（**改造**：新增 `subagents_enabled`（A2 消融）与 `inner_engine` 选型开关）
- `runs/incidents/{id}/hypotheses.md`、`plan.md`（运行时产物）

---

## 任务 T10.1 · spike：DeepAgents vs LangGraph 原生 subgraph

**id**：T10.1 ｜ **所属模块**：M10 ｜ **优先级**：P0

**一句话目标**：两种引擎各实现一版"lead 派 1 个 metrics subagent 取证"的真实 investigate 实现，用实测数据选型，落文档，删掉落选的那份。

**前置知识（≤2 新概念）**
- **多 Agent / subagent 委派**
  1. 一个 lead 把子任务派给专职 subagent，subagent 用**自己独立的上下文**取证，只回一份摘要给 lead。
  2. 价值不在"更聪明"，而在**上下文瘦身**：lead 不用把三个域的原始数据全塞进自己的窗口。
  3. *Java 类比*：主管把活派给专职下属，下属只回结论不回一堆原始材料。
  4. 📖 https://github.com/langchain-ai/deepagents （DeepAgents，需核实锚点）· video-3 P3-6「Multi-Agent vs Skills 架构·代码实战」
- **内层可 checkpoint 恢复性 [R7]**
  1. 选型的关键指标：M12 的审批会让图挂起很久，期间进程可能被 kill；重启 resume 后，**subagent 已经取到的证据和已经形成的假设还在不在**。
  2. 外层 state（`IncidentState`）有 M5 的 checkpointer 保着；问题在于内层引擎的中间态是不是也落进了同一份 checkpoint。
  3. 判定方式很具体：resume 后看网关审计里**同一批查询有没有被重打一遍**——重打了就是内层进度丢了。
  4. *Java 类比*：子流程的中间状态能否随主流程一起持久化恢复。
  5. 📖 https://docs.langchain.com/oss/python/langgraph （subgraphs/persistence，需核实锚点）· video-4 P29-31「AsyncSubAgent 解析」

### 🟡 第 1 段 · 改造（两种引擎，同一个接口，真实驱动）

**不要写两个独立的 spike 脚本**——那种脚本跑完就删，也不会经过 M5 的 webhook、M7 的证据台账、M4 的预算 guard，测出来的恢复性不能代表真实系统。做法是：

1. 在 `nodes/investigate.py` 里定义一个统一的内层调用契约（三种实现共享）：
   ```python
   def run_inner(state, client, evidence_store) -> RcaReport:
       """按 settings.inner_engine 分发：'simple'（M7 的单 Agent）| 'deepagents' | 'subgraph'."""
   ```
2. 分别写 `_run_deepagents(...)` 和 `_run_subgraph(...)`，两者都做**同一件最小的事**：lead 派 1 个 metrics subagent 调真实 `query_metrics` 取一次证，登记 EV，回摘要给 lead。
3. 用 `settings.inner_engine` 切换，**通过 M5 的真实 webhook** 各跑一次同一份告警 fixture。
4. 对比四项，逐项记数字或结论：
   1. **可 checkpoint 恢复性**：见下面的恢复性实验（最重要）；
   2. **代码量**：两份实现各多少行（`wc -l`）；
   3. **API 稳定性**：文档是否与实际 API 对得上、有没有踩到 breaking change；
   4. **视频/资料覆盖度**：卡住时有没有可依赖的讲解（DeepAgents 无视频覆盖，langgraph 原生有 video-2 P58-64 兜底）。

### 🔴 第 2 段 · 自研（恢复性实验）

**自己设计**这个实验，它是选型的决定性证据：
1. 起一次真实事故，等 subagent 开始取证（日志里看到第一次 `query_metrics` 调用）；
2. `kill` uvicorn；
3. 重启，对同一 incident 调 resume（M5 的 `resume_incident_run`）；
4. **判据**：查网关审计表 `select count(*) from audit_log where incident_id='inc-xxx'`——
   - resume 后总数**没有明显增加** → 内层进度保住了；
   - 同一批查询被**重打了一遍** → 内层进度丢了（该引擎在 R7 这一项不及格）；
5. 两种引擎各做一次，把两个数字都记进文档。
6. 结论写进 `docs/dependency-baseline.md`：选哪个、四项各是什么表现、为什么这四项里恢复性权重最高。
7. **删掉落选的那份实现**，`inner_engine` 开关只保留 `'simple'`（A2 对照）与胜出者两个值。

**落笔顺序**
1. 先把 `run_inner` 的分发骨架写好，`'simple'` 分支直接接 M7 已有的实现，确认没接坏。
2. 写 `_run_subgraph`（原生方案，文档最稳，先做这个建立基线）。
3. 写 `_run_deepagents`。
4. 两者各通过真实 webhook 跑一次，记代码量与踩坑。
5. 做恢复性实验，两个引擎各一次，记网关审计数字。
6. 写结论进 `dependency-baseline.md`，删落选实现。

**关键提示（≤3）**
- 结论要落文档并给出"为什么"——这就是面试被问"为什么用 X 不用 Y"的答案，数字比形容词有说服力。
- 时间盒半天：这是选型不是竞赛，能支撑决策就够，别去优化两个都要删一个的实现。
- 恢复性的判据用**网关审计条数**，不要用"日志看起来像是继续了"——重打一遍查询在日志里很容易被误读成正常继续。

**卡住降级路径（30 分钟没思路）**
- 某个引擎跑不起来：先把它降到最小——lead 只派一个 subagent、subagent 只有一个工具，排除是本项目接线问题还是引擎本身问题。
- resume 后判不出来丢没丢：在 `record_evidence` 里打一行带 `EV-*` 编号的日志，resume 后看编号是接着涨还是从头再来。
- 视频：video-4 P16「Multi-Agent 解析」+ P29-31「AsyncSubAgent」建立多 Agent 直觉。
- 问 AI：「DeepAgents 的 subagent 内层状态，在外层 LangGraph checkpointer 下崩溃 resume 后会不会丢？给我一个用网关调用次数来判定的最小实验设计。」

**真实 HTTP 验收（含异常路径）**
```bash
cd iris-agent-platform
uv run uvicorn agent.app:app --port 8000

# 两种引擎各跑一次同一份真实告警（改 settings.inner_engine 后重启）
curl -s -XPOST localhost:8000/webhook/alertmanager -d @some-fault-alert.json
sqlite3 tool-gateway/data/*.db "select count(*) from audit_log where incident_id='inc-A';"

# 恢复性实验：跑到 subagent 取证时 kill，重启后 resume
kill %1 && uv run uvicorn agent.app:app --port 8000 &
curl -s -XPOST localhost:8000/incidents/inc-A/resume
sqlite3 tool-gateway/data/*.db "select count(*) from audit_log where incident_id='inc-A';"
# 判据：resume 前后条数差 ≈ 剩余未做的取证数（没丢）vs ≈ 全部取证数（丢了、重打了一遍）

wc -l src/agent/lead.py src/agent/subagents.py     # 代码量对比
```
判定特征：两引擎都能通过真实 webhook 取到真数据；恢复性差异有网关审计数字为证；`dependency-baseline.md` 有明确选型 + 四项理由；落选实现已删除。依赖真实服务：M2 网关、Prometheus、investigate 模型。

**完成判定**
- [ ] 两种引擎都实现成 `nodes/investigate.py` 的可切换实现，**不是**独立的 spike 脚本
- [ ] 两者都通过 M5 的真实 webhook 驱动、经真实网关取到真数据
- [ ] 恢复性实验用网关审计条数作判据，两个数字都记录在案
- [ ] `dependency-baseline.md` 有选型结论 + 四项理由（恢复性/代码量/API 稳定性/资料覆盖度）
- [ ] 落选实现已删除，`inner_engine` 只剩 `'simple'` + 胜出者

---

## 任务 T10.2 · lead + 三 subagent 上线（接管 investigate）

**id**：T10.2 ｜ **所属模块**：M10 ｜ **优先级**：P0

**一句话目标**：用选定引擎建 lead + metrics/logs/trace 三 subagent 接管 investigate 节点，并把上下文护栏做成工具层硬约束。

**前置知识（≤2 新概念）**
- **最小权限 subagent**
  1. 每个 subagent 只持本域工具，工具集直接取自 M7 的 `tools/registry.py` 分组：

     | subagent | 工具集 | `X-Agent-Role` |
     |---|---|---|
     | `metrics-investigator` | `query_metrics` + `record_evidence` | `metrics-investigator` |
     | `logs-investigator` | `query_logs` + `query_logs_raw` + `record_evidence` | `logs-investigator` |
     | `trace-investigator` | `query_trace` + `record_evidence` | `trace-investigator` |
     | `lead` | `query_cmdb` + `read_evidence_summary` + 派活 | `lead` |

  2. `query_metrics_raw` 只给 lead 或干脆不给——raw 通道全事故只有 5 次预算（M7 T7.4），散给三个 subagent 会被瞬间打光。
  3. `X-Agent-Role` 按 subagent 注入 `GatewayClient`，网关审计里才分得出是谁在查（M2 的 audit_log 有 `agent_role` 列）。
  4. *Java 类比*：按角色授权，专员只拿自己该用的接口权限。
- **offload 上下文护栏做成工具层硬约束 [R11]**
  1. lead 只暴露 `read_evidence_summary(evidence_id) -> str`（读**单份**证据的 summary 段）。
  2. **不提供**任何"列目录 / 批量读 / 读全文"的工具——护栏靠**接口不存在**来保证，而不是在 prompt 里请它别那么做。
  3. 反面做法是在 prompt 里写"请不要批量读证据"：模型在上下文紧张时最容易违背的就是这类软约束。
  4. *Java 类比*：靠接口不暴露来禁止，而非靠注释请求别调。

### 🟡 第 1 段 · 改造（三 subagent + lead 编排）

1. `subagents.py`：按上表定义三个 subagent，每个给一段**本域的取证指引**（如 `trace-investigator` 要写明"trace_id 从 lead 转交的 logs 摘要里取，不要自己编"）；
2. 每个 subagent 建自己的 `GatewayClient(incident_id, agent_role=<自己的角色>)`——**不要共用 lead 的那个**，否则审计里全记成 lead；
3. `lead.py` 编排节奏，四拍：
   1. **建地基**：`query_cmdb` 确认服务拓扑；
   2. **首轮并行广撒**：三个 subagent 同时派出去，各查本域，回摘要；
   3. **汇总更新假设**：把三份摘要合成 `hypotheses.md`（每条假设：描述 / 支持它的 EV / 反对它的 EV / 下一步要什么证据）；
   4. **定向追查**：按假设派第二轮（通常只派 1~2 个 subagent），然后写 `report_draft.md`（结论条条带 `[EV-*]`，格式与 M7 完全一致——M8 的报告节点不区分是谁写的草稿）；
4. `nodes/investigate.py`：`subagents_enabled=True` → lead，`False` → M7 的 `simple_investigator`（消融 A2）。两条路径**共用**同一个 `EvidenceStore` 和同一套回流逻辑（`verifier_feedback` 注入），别各写一份。

### 🔴 第 2 段 · 自研（offload 硬约束 + 内层中间态落回）

**从空文件**写这两件事：
1. **offload 硬约束**：lead 的工具列表里只有 `read_evidence_summary(evidence_id)`，它内部只返回证据文件的 summary 段（不返回 excerpt 全文、不接受通配符、不接受列表参数）。写完自查一遍：lead 有没有任何途径一次性拿到多份证据？
2. **内层中间态落回 [R7]**：把 `hypotheses`（假设列表）和 evidence 索引写回 `IncidentState`（或选定引擎的 subgraph checkpoint），保证 M12 审批挂起 + kill 重启后 resume 不丢——T10.1 的恢复性实验测的就是这条路径能不能成立，这里是把它落到正式实现上。
3. 落盘 `plan.md`（lead 的四拍计划）与 `hypotheses.md`，走 M5 的 `incident_dir()`。

**落笔顺序**
1. 先只定义 `metrics-investigator` 一个 subagent，lead 派它一次，跑通。
2. 补齐 logs / trace 两个，确认三个的 `X-Agent-Role` 在网关审计里分得开。
3. 写 lead 的四拍编排，跑一次真实故障，看 `hypotheses.md` 有没有成形。
4. 自研 offload 硬约束，自查 lead 是否真的无法批量读。
5. 把 hypotheses 落回 state，做一次 kill-resume 验证内层不丢。
6. 接 `subagents_enabled` 分发，两个值各跑一次确认都能出报告（A2 对照组仍然可用）。

**关键提示（≤3）**
- 三个 subagent 严格最小权限，别图省事给它们全量工具——最小权限既是安全叙事，也是让每个 subagent 上下文更干净的手段。
- offload 靠"不暴露批量读工具"硬约束，不靠 prompt 求它。
- 与 M7 的单 Agent **共享** `EvidenceStore` 和回流逻辑，靠 `subagents_enabled` 切换（A2 消融）——两套并行维护必然会漂移。

**卡住降级路径（30 分钟没思路）**
- lead 不派活 / 乱派：检查 lead instructions 里的四拍节奏写清楚了没，以及每个 subagent 的 description 是否说明了"什么时候该派我"。
- 审计里分不出角色：确认每个 subagent 建了自己的 `GatewayClient`，`X-Agent-Role` 不是全走 lead 的默认值。
- 上下文还是爆：先确认 subagent 回给 lead 的是**摘要**不是原始数据；再确认 lead 没有通过某个工具间接拿到了全文。
- 视频：video-4 P22-23「Context-Engineering」讲上下文瘦身思路。
- 问 AI：「我想让 lead Agent 只能读单条证据摘要、不能批量读证据目录，怎么通过'不暴露批量工具'来硬性约束，而不是在 prompt 里请求它？」

**真实 HTTP 验收（含异常路径）**
```bash
cd iris-agent-platform
uv run uvicorn agent.app:app --port 8000

curl -s -XPOST localhost:8000/webhook/alertmanager -d @some-fault-alert.json
ls runs/incidents/inc-*/                    # plan.md / hypotheses.md / evidence/ / report_draft.md 齐全
# 分层可见：网关审计里三个角色都出现过
sqlite3 tool-gateway/data/*.db \
  "select agent_role, count(*) from audit_log where incident_id='inc-xxx' group by 1;"
# 期望：lead / metrics-investigator / logs-investigator / trace-investigator 四行都在

# 异常路径 ①：subagents_enabled=False → 走 M7 单 Agent，仍能出报告（A2 对照组没被改坏）
# 异常路径 ②：审批挂起时 kill 进程重启 → resume 后内层取证进度不丢（验证 R7）
#   判据同 T10.1：resume 前后网关审计条数差 ≈ 剩余未做的取证数，而不是全部重打
```
判定特征：多 Agent 完整排查、四个角色在审计里分层可见；A2 对照组仍可用；kill-resume 内层不丢。依赖真实服务：M2 网关、Prometheus/Loki/Tempo、investigate 模型。

**完成判定**
- [ ] 三 subagent 按上表最小权限持工具，各自 `X-Agent-Role` 在网关审计里分得开
- [ ] raw 通道没有散给三个 subagent（预算只有 5 次）
- [ ] lead 四拍编排成形，`plan.md` / `hypotheses.md` 落盘
- [ ] lead 只有 `read_evidence_summary`，**没有任何**批量读/列目录途径（自查通过）
- [ ] `hypotheses` 与 evidence 索引落回 state，kill-resume 内层进度不丢
- [ ] `subagents_enabled` 切换 lead / M7 单 Agent（A2），两条路径共用 `EvidenceStore` 与回流逻辑
- [ ] lead 产出的 `report_draft.md` 格式与 M7 一致（M8 无需区分来源）

---
*版本 C 特征：多 Agent 全靠"把两种引擎实现成同一个真实节点的可切换实现 → 用真实事故与网关审计数字实测选型 → 自研最小权限分工与 offload 硬约束"，把面试高频的多 Agent 从一次有数据的选型实验一路落到可讲清的设计。*
