# SRE Copilot 执行计划 v2（重写版 · 与学习计划 M0–M12 一一对应）

> 本文是 D4 第二部分：对 `docs/superpowers/plans/2026-07-12-sre-copilot-execution-plan.md` 的重写。
> **裁剪规则**：只保留 ①开源检索评估 ②接口契约/数据模型/验收标准 ③纯真实 HTTP 的端到端(INT)验证。砍掉：单元测试要求、多角色对抗评审、前端测试层、全套标准化台账。
> **一句话定位**：production-minded 本地评估系统，单人学习/受控故障实验/作品演示，非生产系统。
> **风险修复**：正文每处 `[R#]` 对应 D4 风险清单的修复点。

---

## 0. 现实基准（覆盖旧计划的"旧世界"假设）

| 维度 | 旧计划假设 | v2 现实基准 |
|---|---|---|
> **⚠ greenfield 转向（用户决策）**：忽略 iris 项目里所有现有代码（iris-tool-gateway / iris-agent-platform / agent-platform 一律当作不存在）。SRE Copilot 的实现（Java 工具网关 + Python Agent）**全部从 0 新建**。prunus-mume 不是 iris 代码、是外部被测系统，保留。**R4（主干迁移/删重复层）整条作废**——不存在"迁移"，只有"从 0 新建"。

| 维度 | 旧计划假设 | v2 现实基准（greenfield） |
|---|---|---|
| 被测系统 | 自建电商 patient-system（gateway/order/inventory/payment 4 服务） | **prunus-mume**（Spring Cloud Alibaba 刷题平台，pm-auth/user/question/interaction/payment/file-storage 6 服务）[R1] |
| 故障注入 | 自建 ChaosRegistry + `POST /chaos/{fault}?enabled=` | prunus-mume **自带** `ChaosController`（`/chaos/register/{name}`、`/chaos/status`、`/chaos/isEnabled`）+ **允许改其 Java 代码加钩子** + 外部注入（docker/mysql/nacos/tc）[R2] |
| Java 工具网关 | `tool-gateway`（新建） | **从 0 新建**一个干净的 Spring Boot 工程（下称 `tool-gateway/`，现有 iris-tool-gateway 视为不存在）；5 工具 + raw 全部自己写 |
| Python Agent 工程 | `agent-platform`（create_agent 单 Agent） | **从 0 新建**一个干净的 uv + LangGraph 工程（下称 `agent-platform/`，现有 iris-agent-platform / agent-platform 视为不存在），从官方 LangGraph starter 起手 |
| 模型 | deepseek-chat / deepseek-reasoner / Anthropic | **DashScope(Qwen)** 主力 + **OpenRouter**（代理另一家族）；verify 跨家族 = Qwen × OpenRouter 某家族 [R5] |
| 服务名/端口 | 硬编码 `application="order-service"`、静态 cmdb.yaml | 运行时从 **Nacos/CMDB 动态解析**；端口不硬编码 [R3] |
| 多 Agent 内层 | DeepAgents（决策拖到 Sprint 4） | **M10 前置 spike**：DeepAgents vs langgraph 原生 subgraph 对比后锁定 [R6][叉子②] |

> 目录命名：`tool-gateway/` 与 `agent-platform/` 为本计划的干净新建目录名（若磁盘上有同名/近名旧目录，按用户指示"当作不存在"——另起干净目录或清空重建，由你定）。

**§0 契约设计沿用（这些是好设计，从 0 写时照此实现）**：响应信封、错误码、护栏数值、HMAC approval token 契约（§0.5）、证据台账目录约定（§0.6）。**注意：是"照此契约从 0 实现"，不是"复用现有代码"。**

---

## 1. 模块 → 旧任务映射 + 风险修复索引

| 模块 | 一句话 | 替代/重组的旧任务 | 关键风险修复 |
|---|---|---|---|
| **M0** 基建与骨架 | docker-compose 一键起全套 + 一条"注入→观测到信号"闭环 | T1–T3 + 旧 T2 中间件 | R9（就绪门/Nacos 探测） |
| **M1** 接入 prunus-mume + 可观测 + 故障能力勘察与设计 | T4–T12（**不自建系统**，改为接入+勘察+设计故障目录） | R1/R2/R3（勘察 /chaos、对齐 label、设计故障目录） |
| **M2** 工具层契约（Java 网关 5 只读工具 + raw） | T13–T19 | R3（service 动态解析）/R12（重试语义） |
| **M3** 第一个 StateGraph（从 0 建 Python 工程 + 起手线性图） | T28/T30/T31（拆第一步） | ~~R4 作废~~ / R5（模型路由）/R16（拆分） |
| **M4** 状态对象 + 记忆 + 条件边/回流 + 预算 guard | T31（拆第二步） | R8/R13/R14（guard 粒度、state 瘦身、Command 路由） |
| **M5** webhook 入口 + 指纹去重 | T29 | 用 Alertmanager **自带 fingerprint** 替代手搓 sha256 |
| **M6** 分诊节点 triage | T32 | R5（triage 走 DashScope） |
| **M7** 排查简版（单 Agent）+ 证据台账 | T33（= 消融 A2，现役代码已有雏形） | — |
| **M8** 报告节点 + 兜底 + FastAPI 出口 | T34 | — |
| **M9** verify 跨家族验收 | T35 | R5（verify 走 OpenRouter 另一家族） |
| **M10** 多 Agent（spike→lead+subagents） | T38/T39 | R6/R7（spike 前置、内层可 checkpoint）/R11（护栏硬约束） |
| **M11** Skills(SOP) + Chroma 知识库 + 快速路径 | T40/T41/T42/T43 | — |
| **M12** /actions 修复 + HMAC 审批 + HITL + 评估兜底 | T20/T21/T36/T37 + T44–T48（精简） | R10（白名单/dataId 从实际 Nacos 推导） |

依赖主线：M0→M1→M2→M3→M4→{M5,M6,M7}→M8→M9→M10→M11→M12。M2 可与 M1 后半并行（网关用 WireMock 桩上游）。

---

## 2. 各模块技术基线（接口契约 + 数据模型 + 真实 HTTP 验收）

> 每模块只给：**目标 / 接口契约 / 数据模型 / 真实 HTTP 验收（含 ≥1 异常路径）/ 需核实**。落笔顺序、讲解、Java 类比、卡住降级留给 D6 模块文档。

### M0 · 基建与骨架
- **目标**：一份 `deploy/docker-compose.yml` 起 Nacos+MySQL+Redis+Prometheus+Alertmanager+Loki+Promtail+Grafana(+Tempo 或 Zipkin) + prunus-mume 6 服务 + iris-tool-gateway；打通"注入一个故障→Prometheus 看到指标变化"。
- **接口契约**：`deploy/docker-compose.yml`（profiles/healthcheck/depends_on 就绪门 [R9]）；`deploy/scripts/check-env.sh`（PASS/FAIL 清单，含 Nacos 就绪 `curl .../nacos/v1/console/health/readiness`）。
- **数据模型**：端口表（**从各服务 Nacos 实际配置回填**，不硬编码 [R3]）；中间件版本基线 `docs/dependency-baseline.md`（精确 tag，ARM64）。
- **真实 HTTP 验收**：
  - `docker compose ps` 全部服务 Up/healthy；
  - `curl -s localhost:9090/-/ready` → 200；`curl -s localhost:3100/ready` → 200；
  - `curl -s "localhost:8848/nacos/v1/console/health/readiness"` → 200；
  - 异常路径：故意停 Nacos（`docker stop nacos`）→ 任一 prunus-mume 服务健康检查转 unhealthy，check-env 报出该阻塞。
- **需核实**：prunus-mume 各服务在 Nacos 里的实际端口；MySQL/Redis 账号；prunus-mume 是否需要预置 Nacos 配置集才能启动。

### M1 · 接入 prunus-mume + 可观测接线 + 故障能力勘察与设计
- **目标**：让 prunus-mume 6 服务产出 metrics/logs/traces 三路真实信号；勘察其 `/chaos` 现有能力；设计落到刷题域的故障目录。
- **接口契约**：
  - **勘察**（Phase 1 第一任务）：`curl -s localhost:<port>/chaos/status` 枚举每个服务已注册的 chaos flag；记录 `/chaos/register/{name}`、`/chaos/isEnabled` 的入参与语义（**需核实鉴权/参数体**）[R2]。
  - **可观测接线**：确认/补齐 prunus-mume 的 actuator + `micrometer-registry-prometheus`（**需核实是否已接**）；Promtail `docker_sd_configs` 采日志；tracing 到 Tempo/Zipkin；日志 pattern 含 traceId（这是 M2 query_logs 的契约）。
  - **告警规则**：`deploy/alert-rules.yaml`，label 对齐 prunus-mume **真实 `application` 值** [R3]。
- **数据模型**：`docs/fault-catalog.md`——按 prunus-mume 服务设计的故障目录（每条：fault_id / fault_class / root_service / 注入手段(自带chaos/新增钩子/外部) / expected_alerts / causal_chain / key_evidence）。故障类型覆盖 SRE 诊断要练的：慢查询(pm-question 题库查询)、线程池耗尽/下游超时(pm-payment 支付渠道)、缓存故障(Redis)、配置变更(Nacos 发布)、实例宕/CrashLoop、CPU/磁盘饱和、GC 压力、网络延迟。允许改 prunus-mume Java 代码加钩子 [叉子①]。
- **真实 HTTP 验收**：
  - 三路贯通：从某服务日志取一个 traceId → 在 Tempo/Zipkin `GET /api/v2/trace/{id}` 看到跨服务 span → 同 traceId 在 Loki `query_range` 查得到；
  - 注入一个故障 → ≤2min Alertmanager `/api/v2/alerts` 出现 expected_alert → revert → 告警 resolve；
  - 异常路径：一条"背景正常变更/瞬时尖峰"不触发告警（阴性对照素材）。
- **需核实**：prunus-mume 是否已内置 actuator/micrometer/tracing；其 `/chaos` 已注册哪些 flag；Nacos dataId/group 命名约定（M12 的 revert_config 依赖）。

### M2 · 工具层契约（Java 网关 5 只读工具 + raw 通道）— **从 0 新建**
- **目标**：从 0 建一个干净的 Spring Boot 工具网关，把 5 个只读工具（metrics/logs/trace/cmdb/changes）+ raw 通道打到真实 Prometheus/Loki/Tempo；service 动态解析。
- **接口契约**（照 §0.3 信封/错误码/护栏**从 0 实现**）：
  - **从 0 建**：网关骨架（鉴权拦截器 + 统一信封 + 错误码 + 审计表）+ `POST /api/v1/tools/query_metrics|query_logs|query_changes|query_cmdb`；
  - `query_trace`（find_slow/find_error/get_trace 三模板，span 树精简）、`raw/promql`、`raw/logql`（静态校验 + 独立预算 + 审计高亮）；
  - service 参数**运行时查 CMDB（从 Nacos 拉取）动态校验**，不用静态常量集 [R3]；
  - 重试语义统一：只读工具在 Python GatewayClient 侧幂等重试 1 次 [R12]。
- **数据模型**：`ApiEnvelope{ok,degraded,degraded_reason,data,meta{elapsed_ms,truncated,budget_remaining}}`；audit_log 表（含 template_or_raw、agent_role）；per-incident 预算表（模板 40 / raw 5）。
- **真实 HTTP 验收**（打真实网关 + 真实上游）：
  - 5 工具各 `curl` 一次，返回信封字段齐全、data 有真实数据；
  - degraded：`docker stop loki` → query_logs 返回 `degraded:true` 且 HTTP 200（非 5xx）；
  - 429：同一 `X-Incident-Id` 连打 41 次模板工具 → 第 41 次 `BUDGET_EXCEEDED`；
  - 400：window=12h → `RANGE_TOO_LARGE`；raw 无 label selector → 403 `NOT_WHITELISTED`。
- **需核实**：prunus-mume 的实际指标名（题库查询耗时、支付线程池、缓存计数）以确定 query_metrics 模板表达式。

### M3 · 第一个 StateGraph（从 0 建 Python 工程 + 起手线性图）[R16 第一步]
- **目标**：从 0 起一个干净的 uv + LangGraph 工程（从官方 starter 起手）；配置 + 多模型路由；跑通"线性 StateGraph 单节点调 1 个真实工具"。
- **接口契约**（全部**从 0 新建**）：
  - `config.py` `Settings`（网关 base_url/token、4 角色模型名、阈值、消融开关）；
  - `models/router.py` `get_model(role: "triage"|"investigate"|"verify"|"report") -> BaseChatModel` + `get_embeddings()`——**走 DashScope + OpenRouter 官方 integration** [R5]；
  - `tools/client.py` `GatewayClient(base_url,token,incident_id,agent_role).call(path,body)->Envelope`（429→`BudgetExceededError`）；`tools/definitions.py` `make_tools(client)`（7 个 @tool，Pydantic args_schema，degraded 透传）；
  - `graph.py` 起手：`StateGraph` 单节点 `investigate_once`→调 `query_metrics`→END。
- **数据模型**：`Envelope`（Pydantic 镜像网关信封）。
- **真实 HTTP 验收**：
  - `uv run python scripts/smoke_models.py` → DashScope 与 OpenRouter 各一次真实 invoke + structured_output 全 PASS（不 mock）；
  - `curl -XPOST localhost:8000/run -d '{alert fixture}'` → graph 调到真实 `query_metrics`，返回真实指标数据；
  - 异常路径：网关 token 错 → 工具层抛 `GatewayRequestError`，graph 优雅结束不崩。
- **需核实**：LangGraph 官方 starter（`langgraph new` 模板）的当前 `langgraph.json`/`graph.py` 约定；如何暴露 HTTP 触发（`langgraph dev` 自带 API vs 自挂 FastAPI）。

### M4 · 状态对象 + 记忆 + 条件边/回流 + 预算 guard [R16 第二步]
- **目标**：把线性图升级为带状态对象、checkpointer、条件边回流、预算 guard 的骨架。
- **接口契约**：
  - `graph/state.py` 定义全套（见数据模型）；
  - `AsyncSqliteSaver` checkpointer，`thread_id=incident_id`；
  - 条件边：verify→pass→report；fail 且 `investigate_rounds<2`→investigate（rounds+1）；fail 耗尽→report；
  - `graph/guard.py`：预算检查改为**调用粒度**——在 GatewayClient 回调/工具封装内累计 token 并在 agent 循环内检查（不只节点前置）[R8]；超限用 **`Command(goto="report")`** 显式路由 [R14]。
- **数据模型**（`IncidentState` TypedDict，**大文本落盘、state 存引用** [R13]）：
  - `incident_id, alerts(引用 alert.json), fingerprint, matched_cases[], triage:TriageResult|None, investigate_rounds:int, verifier_feedback[], draft_report_ref(路径,非全文), verify_verdict:Verdict|None, budget:BudgetLedger, approval:ApprovalDecision|None, remediation_result:RemedyOutcome|None`；
  - 子模型：`TriageResult(severity,suspected_domains[],fast_path,candidate_root)`、`RcaReport(root_service,root_cause,root_detail,confidence,causal_chain[],summary_md,evidence_ids[],remediation?)`、`Verdict(verdict,objections[])`、`BudgetLedger(tokens_used,token_limit,deadline_ts,budget_exhausted)`。
- **真实 HTTP 验收**：
  - 灌 fixture 告警 → 图从 ingest 走到 close，`checkpoints.db` 有该 thread；
  - 强制 verify 返回 fail → 回流 2 次后走 report（rounds 正确）；
  - deadline 置过去 → guard 跳过 investigate 直达 report；
  - 异常路径：投毒使某节点抛异常 → 异常持久化到 incident 表可观察，进程不裸崩。

### M5 · webhook 入口 + 指纹去重
- **接口契约**：`POST /webhook/alertmanager`（AM v4 格式）；30min 内同指纹且 open→merge 返回 `{deduped:true}` 不启图；否则建 incident 异步启图；坏 payload 落 `runs/deadletter/`。
- **数据模型**：`Alert(alertname,service,severity,status,starts_at,labels,annotations)`；**指纹优先用 Alertmanager 自带 `fingerprint` 字段**，回退才自算 `sha256(alertname+service+关键labels)[:16]`；`incident(incident_id PK,fingerprint,status,created_at,alerts_ref)`。
- **真实 HTTP 验收**：`curl -XPOST localhost:8000/webhook/alertmanager -d @am-fixture.json` → 200 且 incidents.db 落记录；同指纹 31 分钟后新建、30 分钟内 deduped；坏 payload → 200（防 AM 重试风暴）且死信文件生成。

### M6 · 分诊节点 triage
- **接口契约**：`graph/nodes/triage.py`——`get_model("triage").with_structured_output(TriageResult)`（DashScope 轻模型）；校验失败重试 1 次，再失败降级默认 `TriageResult(severity="P2",suspected_domains=全部,fast_path=False)`；`matched_cases[0].similarity>=fast_path_similarity`→fast_path。
- **数据模型**：`TriageResult`（见 M4）；疑似域枚举与 M11 Skills 预筛映射一致。
- **真实 HTTP 验收**：对某故障真实告警 fixture 调**真模型**一次，`suspected_domains` 命中该故障对应域；异常路径：喂畸形告警 → 走默认值路径不崩。

### M7 · 排查简版（单 Agent 直连工具）+ 证据台账
- **接口契约**：`agents/evidence.py` `EvidenceStore(incident_id)`：`new_id(kind)->EV-M-001`、`write(...)->Path`（§0.6 格式，防路径穿越）、`list_ids/read`；`agents/simple_investigator.py`：LangChain v1 `create_agent(model,tools,response_format=RcaReport)` + `record_evidence` 工具；结论必引 `[EV-*]`；token 累加进 budget。此实现**永久保留为消融 A2**。
- **数据模型**：证据文件 frontmatter（evidence_id/source/agent_role/ts/degraded）+ 正文（≤3 句摘要 + 关键数据摘录）。
- **真实 HTTP 验收**：注入某故障 → webhook → `runs/incidents/inc-*/` 生成 evidence/*.md 若干 + report_draft（链路通即可，root_service 不强求）；异常路径：网关 degraded 时证据仍带 degraded 标注落盘。

### M8 · 报告节点 + 确定性兜底 + FastAPI 出口
- **接口契约**：`graph/nodes/report.py`——轻模型润色 `summary_md`，**结构化字段原样保留不许 LLM 改写**；LLM 失败→`report_template.render()` 兜底；落 `runs/incidents/{id}/report.md`；`GET /incidents/{id}` 出报告。
- **数据模型**：`RcaReport`（见 M4）；模板骨架（结论/置信度/因果链/证据引用清单/修复建议/异议）。
- **真实 HTTP 验收**：真实跑出 `report.md` 含全部 section 与 `[EV-*]`；`curl localhost:8000/incidents/{id}` 返回结构化字段；异常路径：断网 LLM → 走模板兜底仍出报告。

### M9 · verify 跨家族验收节点 [R5]
- **接口契约**：`graph/nodes/verify.py` 两段式——①**确定性预检**：正则抽 `EV-[MLTS]-\d{3}`，文件不存在→直接 `Verdict(fail,["引用不存在"])` 不花 LLM；②**LLM 跨家族核验**：`get_model("verify")`（**OpenRouter 某家族，与 investigate 的 DashScope 异厂商**）；输入只有证据全文 + 草稿报告（不含主 Agent 推理，保独立性）；`with_structured_output(Verdict)`；消融 A3：`verifier_enabled=False`→恒 pass。
- **真实 HTTP 验收**：篡改报告引用一个不存在的 EV → 确定性 fail（不调 LLM）；真实证据链跑一次跨家族核验得 Verdict；异常路径：verify 模型输出解析失败 → 记 `verifier_absent`，report 标注"验收缺席"。

### M10 · 多 Agent（spike 决策 → lead + subagents）[R6][R7][R11][叉子②]
- **前置 spike**（M10 第一任务）：DeepAgents vs langgraph 原生 subgraph 各实现一个"lead 派 1 个 metrics subagent 取证"的最小样例，用真实网关跑一次；评估 checkpoint 可恢复性、代码量、API 稳定性；结论写 `docs/dependency-baseline.md`，锁定内层引擎。
- **接口契约**（选定引擎后）：
  - `agents/subagents.py`：`metrics-investigator`(仅 query_metrics)、`logs-investigator`(query_logs+raw_logql)、`trace-investigator`(仅 query_trace)，各带 record_evidence；**最小权限**，`X-Agent-Role` 按 subagent 注入；
  - `agents/lead.py`：lead 持 query_cmdb/query_changes + 调度；节奏：首轮并行广撒 → 汇总更新 hypotheses → 定向追查 → 写 report_draft；
  - **offload 护栏做成工具层硬约束** [R11]：lead 只暴露"读单个 evidence_id 摘要"工具，**不提供批量读目录工具**（而非仅写进 prompt）；
  - **内层关键中间态**（hypotheses、evidence 索引）落回 IncidentState 或 subgraph checkpoint，保证 resume 不丢 [R7]；
  - `graph/nodes/investigate.py`：`subagents_enabled` True→lead，False→simple_investigator（A2）。
- **真实 HTTP 验收**：注入某故障 → 完整跑 investigate → `plan.md/hypotheses.md/evidence/*/report_draft.md` 齐全，分层 trace（LangSmith 或日志）可见 lead→subagent；异常路径：审批挂起时 kill 进程重启 → resume 后内层进度不丢（验证 R7 修复）。

### M11 · Skills(SOP) + Chroma 知识库 + 快速路径
- **接口契约**：
  - `skills/loader.py`（自研）：扫 `skills/*/SKILL.md` 解析 frontmatter；`listing_for(domains)` 按疑似域预筛（映射表：db/threadpool/jvm/resource/container/config/cache→对应 skill；config-change-first 恒列）；`read_skill(name)` 全文≤600 token；采用 **anthropics/skills 的 SKILL.md 三段式格式**；
  - `knowledge/store.py`：`langchain-chroma` `PersistentClient`，`add_case/search(k=3)->KnowledgeHit[]`，检索异常返回 `[]` 不阻塞；
  - `knowledge_match`（告警→search→matched_cases）、`knowledge_update`（**仅 verdict=pass 且 approval.approved 才入库**，驳回只落 Markdown）；
  - 快速路径：`triage.fast_path` True→lead instructions 注入候选根因"优先最小取证验证"。
- **数据模型**：`KnowledgeHit(case_id,similarity,summary,root_service,root_cause)`；SKILL.md frontmatter(name/description/version)；案例 Markdown（症状/根因/证据/修复/人工备注），向量化文本=症状段（检索键是症状不是结论）。
- **真实 HTTP 验收**：真实 embedding 下 add→search 命中且相似度排序正确；驳回案例不进向量库但 Markdown 落盘；快速路径二次注入同故障 → 网关审计 `count(*) where incident_id` 步数显著低于首次（记两个数字）；异常路径：Chroma 检索异常 → 返回空不抛。

### M12 · /actions 修复 + HMAC 审批 + HITL + 评估兜底 [R10]
- **接口契约（修复通道）**：
  - 网关 `POST /api/v1/actions/execute`（action 枚举 restart_container/kill_slow_query/revert_config/disable_chaos_flag；target 白名单**从 prunus-mume 实际服务名 + redis 推导** [R10]；dryRun=true 只需服务 token、返回预演；dryRun=false 需 approval token）；
  - `ApprovalTokenVerifier`（逐字 §0.5：ver/iss/aud/iat/exp/kid + nonce 一次性 + 常量时间比较）；
  - `revert_config` 的 Nacos dataId/group **从 prunus-mume 实际约定映射** [R10]（M1 已勘察）；
  - Python 侧 `security/token.py`（**itsdangerous 签名 + 自研 nonce 持久层**）；`graph/nodes/approval.py`（`interrupt()` 挂起，进 interrupt 前先 dry-run 拿预演）；`graph/nodes/remediate.py`（带 token 执行 → 60s 后 query_metrics 复查恢复）；审批 API `GET /incidents/{id}/pending`、`POST /incidents/{id}/decision` + CLI `copilot approve/reject`。
- **接口契约（评估兜底，精简）**：`evals/scorer.py`（纯函数三级判分 svc/cls/anchor + 阴性）；`evals/runner.py`（注入→等告警→诊断→revert→判分，JSONL）；`make smoke`（5 例<15min 门禁，acc@service 不回退）；A0–A3 消融开关。**不写单元测试台账**。
- **数据模型**：approval token payload（§0.5）；`action_audit(id,ts,incident_id,action,target,dry_run,token_fingerprint,result,detail)`；`CaseScore(svc_hit,cls_hit,anchor_hit,negative_ok)`；真值 `eval-control/cases/*.yaml`（Agent 运行时不可读）。
- **真实 HTTP 验收**（端到端 INT）：
  - **F09 类（配置变更）全流程**：webhook→报告→CLI 审批→`revert_config` 真实执行→复查 recovered=True→案例入 Chroma；
  - 无 token `curl` execute → 403；伪造签名/过期 exp/重放 nonce → 403；dry-run 无 token → 200 且 action_audit 落 `dry_run=1`；
  - 审批挂起时 kill uvicorn 重启 → `copilot approve` 仍能 resume（checkpointer 实证）；
  - `make smoke` 一条命令 <15min 退出码 0；把 baseline 改高 → 退出码 1（门禁生效）；
  - **异常/纪律**：慢查询类故障只输出"人工补索引/升级"建议，**不得**把 kill_slow_query 当根因修复、不得谎报 recovered=True。

---

## 3. 端到端(INT)真实 HTTP 链路总验收（替代旧 CP0–CP6，纯真实链路）

| 里程碑 | 端到端真实验收（无 mock） |
|---|---|
| 环境就绪（M0–M1） | compose 全 Up；三路 metrics/logs/trace 同 traceId 贯通；注入一故障→告警→revert→resolve |
| 数据面就绪（M2） | 5 工具 + raw 真实 curl 全过；degraded/429/400/403 四类异常路径演示 |
| 骨架闭环（M3–M8） | 注入故障→webhook→图走完→`runs/incidents/*/` 出证据+报告（含 [EV-*]） |
| 智能闭环（M9–M11） | 篡改引用被 verify fail；多 Agent 分层 trace；相似案例快速路径步数下降 |
| 全闭环（M12） | F09 全链路 webhook→报告→审批→revert_config→复查恢复→入库；崩溃 resume；token 防伪防重放；make smoke 绿 |

---

## 4. Out-of-scope（知情不做，沿用旧计划并对齐现实）
多租户/SaaS/OAuth·接入真实生产·全自动跳过审批·消息队列/独立向量库服务·SkyWalking/ES·OPA/分布式限流·审批 Web UI·LLM judge 判分·压测框架·网关熔断框架。**新增**：不自建电商 patient-system（改用 prunus-mume）；不追求 prunus-mume 全业务功能正确（只需能产出故障信号）。

---

## 5. 遗留"需核实"清单（M0/M1 勘察任务的输出，不臆造）
1. prunus-mume 6 服务在 Nacos 的实际端口、MySQL/Redis 账号、启动所需预置配置集。
2. prunus-mume 是否已接 actuator/micrometer/tracing；未接则 M1 需补接。
3. prunus-mume `/chaos` 已注册哪些 flag、`/chaos/register` 入参与鉴权。
4. prunus-mume 各服务真实指标名（题库查询耗时/支付线程池/缓存计数），决定 M2 query_metrics 模板。
5. prunus-mume Nacos dataId/group 命名约定，决定 M12 revert_config 白名单映射。
6. OpenRouter 实际可用模型家族清单（决定 verify 跨家族具体型号）、DashScope 可用模型 ID。
