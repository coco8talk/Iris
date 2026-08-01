# D4《execution-plan 架构评审报告》(第一部分：风险清单，待用户确认)

> 评审对象：`docs/superpowers/plans/2026-07-12-sre-copilot-execution-plan.md`（1067 行，T1–T51）
> 评审基准：D2 确认的 8 项决策 + 现实（prunus-mume / iris-tool-gateway / iris-agent-platform / DashScope+OpenRouter）
> 输入来源：SA-5 架构评审 + SA-6 开源检索 + 主 agent 通读全文
> **本文是评审报告，不是重写后的计划。风险清单经你确认后，才产出第二部分「重写后的执行计划全文」。**

---

## 0. 定性结论（先说好话，再说问题）

这份执行计划**质量很高**：51 任务、§0 完整契约（响应信封/错误码/护栏数值/HMAC token 契约/证据格式/模型路由）、CP0–CP6 检查点、A0–A3 消融实验、纯函数判分 + 评估 harness。它走的**正是你选的路线**（LangGraph 显式 `StateGraph` + DeepAgents 多 subagent + verify 跨家族 + Skills + Chroma + `/actions` + HMAC + HITL）。

所以问题**不是"计划走错了路"**，而是两条：
1. **它面向的是旧世界**——自建电商"病人系统"、`tool-gateway`、`agent-platform`、deepseek/anthropic 模型。而你确认的现实是 prunus-mume（刷题 6 服务）、iris-tool-gateway、iris-agent-platform、DashScope+OpenRouter。
2. **它面向的是有经验的 agentic worker**——单个 T31 一次交付"10 节点 + 条件边 + checkpointer + 预算 guard + 异步执行器"。对 Python/agent 零基础的你，这是最大卡壳源；M0-M12 学习计划要把这些 L 级任务重切成"学一个概念→做一个能 curl 验收的小任务"。

**SA-6 带来一条重要去风险**：`deepagents` 是 LangChain 官方库、真实活跃；且有三个近乎同构的可复刻参考——**OpenSRE**（LangGraph+并行 subagent+证据链 RCA）、**HolmesGPT**（CNCF，最成熟 alert→RCA）、**redis-sre-agent**（LangGraph+Prom/Loki 接线）。这正好喂给"复刻→改造→自研"的教学法。

---

## 1. 风险清单（按严重度降序，P0 阻塞 / P1 重要 / P2 优化）

### P0（不改则 Phase 1/Phase 4 大面积返工）

| # | 风险 | 影响 | 修改建议 | 依据 |
|---|---|---|---|---|
| R1 | 全盘为"自建 4 服务电商病人系统"设计，与 prunus-mume（6 服务刷题平台）不符 | Phase 1（T4–T12）整段"建系统"作废/重做；F01–F13 故障目录、传播链、真值标签全部基于虚构 order/inventory/payment | 删除自建 patient-system，改为"接入 prunus-mume"任务组；按 pm-* 实际服务重写故障目录与拓扑 | exec T4–T12；PLAN §2、§5.2 |
| R2 | chaos 契约不符：计划用自建 `POST /chaos/{fault}?enabled=`；prunus-mume 自带 `/chaos/register/{name}`、`/chaos/status`、`/chaos/isEnabled` | T4 自建 ChaosRegistry 冗余；T21 `disable_chaos_flag`、T23/T24/T26 注入器全部打向不存在的端点 | 删 T4 chaos 框架，适配 prunus-mume 现有 register/isEnabled 语义（**需先勘察它已注册了哪些故障标志**） | exec T4/T21/§5.1 |
| R3 | 指标模板/服务名/端口硬编码（`application="order-service"`、`payment.executor`、静态 `cmdb.yaml`）；prunus-mume 端口在 Nacos、服务名不同 | T14 全部 PromQL 模板、T17 CMDB、T18 枚举校验、告警 label 与真实指标对不上，query_metrics 返回空 | service 改运行时从 Nacos/CMDB 动态解析；CMDB 从 Nacos 拉取而非静态 YAML；告警 label 对齐真实 `application` 值 | exec T14/T17/T18 |
| R4 | Python 主干应为 iris-agent-platform（LangGraph starter 壳），但计划全落在 `agent-platform/src/sre_copilot`；两处工具层近乎重复 | 目录双写、工具层漂移；starter 的 graph.py 仍是 `changeme` 单节点，无迁移任务 | 增加"以 iris-agent-platform 为主干、迁 agent-platform 工具/证据台账、删重复层"的前置任务，统一目录树 | 确认项 主干；SA-1 |
| R5 | 模型路由写死 deepseek/anthropic，与 DashScope(Qwen)+OpenRouter 不符；跨家族 verify 未按两家族接线 | T28 用 `langchain-anthropic/deepseek`、成本估算依赖"Anthropic prompt caching"，全部失真；H8 落点错 | §0.4 重定义：investigate 走 DashScope、verify 走 OpenRouter 另一家族；换 integration；重估成本 | exec §0.4/T28/T35 |

### P1（承重、需前置排雷）

| # | 风险 | 影响 | 修改建议 | 依据 |
|---|---|---|---|---|
| R6 | DeepAgents 为排查承重件，但库无视频覆盖、API 迭代快，框架决策却推迟到 Sprint 4（T38） | 到 Sprint 4 才撞 breaking change，需全量回退 langgraph subgraph，风险集中爆发 | 前置 spike：P0/P1 就在 deepagents vs langgraph 原生 subgraph/supervisor 间锁定主线（见下方叉子②） | exec T38/T39 |
| R7 | 嵌套两套编排（LangGraph 外 + DeepAgents 内），内层排查进度**不进 checkpointer** | approval 挂起/崩溃 resume 后，investigate 内层已完成的取证/假设丢失，只保 rounds 计数，重复烧预算 | investigate 关键中间态（hypotheses/evidence 索引）落回 IncidentState；或改 langgraph subgraph 使其可 checkpoint | exec T31/T39 |
| R8 | 预算 guard 是"节点前置"粒度，非 LLM-调用粒度 | investigate 节点内多轮循环可在两次 guard 检查之间冲破 token/墙钟上限（H6 形同虚设） | 在工具封装层/回调内累计 token 并在循环内检查；或对 investigate 设内层步数硬上限（H5 网关 429 作硬兜底） | exec T31 guard.py |
| R9 | prunus-mume 强依赖 Nacos+DB+Redis，从零 compose 需保证 6 服务启动时序；Nacos 挂则全链路不起 | 环境搭建/联调最易卡壳；Nacos 单点成为 Agent 联调隐性阻塞 | compose 显式 healthcheck + depends_on 就绪门；Nacos 就绪探测纳入 check-env.sh；文档标 Nacos 排障路径 | exec T2/T8 |
| R10 | `revert_config`/action 白名单假设 dataId=`<service>.yaml` 且为 4 服务名 | 修复通道对 prunus-mume 的 Nacos dataId/group 命名对不上，`revert_config`/`disable_chaos_flag` 失效 | 白名单与 dataId 映射从 prunus-mume 实际 Nacos 配置推导（需核实其 dataId/group 约定） | exec T21 |

### P2（优化，可排后）

| # | 风险 | 影响 | 修改建议 |
|---|---|---|---|
| R11 | offload 上下文护栏（§7.4）实为 prompt 指令，违背"护栏是 LLM 之外硬约束"原则 | lead 可无视规则批量读证据，上下文瘦身不可靠，A2 消融论证被削弱 | 把"不得直读全量证据"做成工具层硬约束（只暴露摘要检索工具，禁批量读目录） |
| R12 | 500 `GATEWAY_ERROR` 重试语义自相矛盾（PLAN §4.1 要重试 1 次，T30 说不重试） | 行为不一致 | 统一：只读工具的重试策略放 GatewayClient，幂等可安全重试 1 次 |
| R13 | IncidentState 携带完整 `summary_md`+`alerts_json` 且每节点 checkpoint | 长报告/多告警下 SqliteSaver 全量序列化膨胀、写放大 | 大文本落盘，state 只存 incident 目录引用/ids |
| R14 | budget guard 描述为"装饰器返回路由指令跳 report" | LangGraph 节点返回 state、路由由条件边/`Command(goto)` 决定，当前描述不直接成立 | 明确用 `Command(goto="report")` 或统一置标志 + 每条出边前置条件 |
| R15 | 成本/延迟指标依赖 Anthropic prompt caching 与 LangSmith usage 归集 | 换 DashScope/OpenRouter 后 caching/usage 口径不同，$50–120 估算失真 | 以实际 provider 的 usage 字段重定成本采集口径 |
| R16 | T31 单个 L 任务一次交付 10 节点+条件边+checkpointer+guard+异步执行器 | 零基础读者早期最大卡壳点 | 拆分：线性图跑通→加条件边/回流→加 interrupt/审批→加 guard，每步单独可验（M2/M3 承接） |

---

## 2. 关键架构决策标注（为什么这么定 / 备选 / 何时推翻）

| 决策 | 为什么这么定 | 备选方案 | 什么情况下需要推翻它 |
|---|---|---|---|
| 外层 LangGraph 显式状态机，create_agent 仅作内层/热身 | 确定性主干+智能节点，走向由代码条件边控制，可审计可回放（H9） | 全程 create_agent 自由 Agent；纯 supervisor | 流程分支实际很少、状态机沦为形式，或更需快速原型 → 回退单 Agent |
| investigate 内层多 subagent 并行取证 | 上下文隔离 + 最小权限（每 subagent 只持本域工具） | langgraph 原生 subgraph / langgraph-supervisor（可 checkpoint、无第三方 API 风险）；DeepAgents（三件套现成） | 见叉子②：checkpoint 需求 vs 现成度的权衡 |
| IncidentState 单一大 TypedDict 贯全流程 | incident_id 贯穿、单 thread 便于 checkpoint/回放 | 大文本外置、state 只存引用；分段 sub-state | 长报告/多告警致 checkpoint 膨胀 → 瘦身 state（R13） |
| Java 工具网关走 HTTP（非 MCP） | 数据面单一收口，白名单/审计/降级/预算集中；复用 Java 存量技能 | MCP server 暴露工具；gRPC | 需多 Agent 客户端标准化接入/跨进程工具发现 → 转 MCP（本项目单进程收益低，HTTP 合理） |
| checkpointer 用 SqliteSaver（thread_id=incident_id） | 单人本地、审批挂起可恢复、崩溃恢复足够、零运维 | Postgres saver（并发/HA）；内存 saver | 并发 incident 多致 SQLite 单写锁竞争、或需多进程共享 → 上 Postgres |
| verify 跨家族核验（排查/验收异厂商，H8） | 防同源偏差，反幻觉；确定性预检 + LLM 语义核验分层 | 同家族+温度扰动；多数投票 | 换 DashScope+OpenRouter 后必须重接线；若 A4 证明跨家族增益不显著 → 降级同家族省成本 |
| /actions：单 interrupt 落点 + HMAC 一次性令牌 + dry-run | 修复与查询物理隔离、人在环唯一收口、令牌版本化/nonce/常量时间验签 | OAuth/OPA 策略引擎；多审批点 | 需多级/细粒度授权或接真实生产 → 补身份与授权层（当前单点+HMAC 对本威胁模型恰当） |

---

## 3. 开源复用结论（SDD 阶段 0，SA-6）

| 能力 | 建议 | 采用件（真实 URL） |
|---|---|---|
| 多 Agent 编排 | 改造采用 / 见叉子② | deepagents（官方）· langgraph-supervisor · langgraph 原生 subgraph |
| SRE/RCA 参考（复刻教学素材） | 重点借鉴 | OpenSRE（LangGraph+并行 subagent，架构同构）· HolmesGPT（CNCF）· redis-sre-agent |
| HITL 审批 | 直接采用 | LangGraph 官方 `interrupt()` + HITL middleware |
| Alertmanager 去重 | 采用其契约（自研解析） | Alertmanager 自带 `fingerprint` 字段，比手搓 sha256 更简单 |
| Chroma 案例记忆 | 直接采用 | langchain-chroma 官方集成 |
| Skills 格式 | 直接采用格式 | anthropics/skills（SKILL.md 三段式） |
| HMAC 签名 | 直接采用 | itsdangerous（签名+过期）+ 标准库 `hmac.compare_digest` |
| **诚实自研点** | 自研 | 一次性 nonce 持久层 · 跨家族互验编排 · LangGraph 侧 Skills loader · /actions 受控执行胶水 |

---

## 4. 待用户裁决的两个叉子（决定重写方向，见对话中的提问）

- **叉子①（故障目录落地）**：13 类电商故障绑死在库存删索引/支付线程池上，无法平移到 prunus-mume 的业务域。且 prunus-mume 自带 `/chaos` 具体注册了哪些故障标志，尚未勘察——重写方向取决于"你愿不愿意改 prunus-mume 的 Java 代码来加故障钩子"。
- **叉子②（多 Agent 内层引擎）**：DeepAgents（官方现成、但内层状态不进 checkpointer、API 迭代快）vs LangGraph 原生 subgraph（可 checkpoint、无依赖风险、与"显式状态机"一致、但要多写编排代码）。直接影响 R6/R7 和 M9 的写法。

裁决后，产出 **D4 第二部分：重写后的执行计划全文**（按 SDD 裁剪规则：只保留 阶段0 开源检索 + 阶段3 接口契约/数据模型/验收 + 阶段5 INT 纯真实 HTTP 链路）。
