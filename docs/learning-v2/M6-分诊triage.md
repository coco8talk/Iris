# 模块 6 · 分诊节点 triage — 版本 C

> 编排理念：🟡在本项目真实代码上改造（把 M4 塞在 `graph.py` 里的占位节点拆进 `nodes/` 子包，再加第一个真正用 LLM 的节点）→ 🔴自研结构化输出的失败降级。**这是第一次把 LLM 的自由文本变成可靠契约。**

**本模块的能力边界（先对齐）**
- **入口是 M5 的自建 FastAPI，端口 8000**：所有验收都走 `POST /webhook/alertmanager`，`langgraph dev`/2024 从 M5 起只作 Studio 调试用。
- **checkpointer 已经有了**（M5 T5.2），所以可以直接 `sqlite3 runs/checkpoints.db` 看某个 thread 的 state 里 triage 字段长什么样。
- **工具集不变**：仍只有 `query_metrics`，triage 节点本来也不调工具（它只看告警文本）。其余 6 个工具在 **M7** 接入。
- **本模块只定义 `TriageResult` 的前两个字段**：`fast_path` / `candidate_root` 依赖 `matched_cases`，而 `matched_cases` 要等 **M11** 的 Chroma 检索才有真实来源——在这里写它们只能靠手造假相似度验证，所以整块留给 M11。

## 学完本模块你能做到的 3 件事
1. 建出 `nodes/` 子包，把图的节点从 `graph.py` 里拆出来，`langgraph.json` 一个字不改。
2. 让轻模型（DashScope）把一组真实告警判成结构化的 `TriageResult`（严重度 + 疑似域），并说清疑似域枚举为什么必须和后面的 Skills 预筛逐字一致。
3. 模型输出解析失败时自动降级为"全面排查"，绝不让流程卡死。

## 本模块交付物清单（文件路径级）

> 从本模块开始建 `nodes/` 子包（M4 时只有一个节点，不值得拆；现在节点开始变多了）。`state.py`/`graph.py`/`guard.py` **仍留在包顶层**，所以 `langgraph.json` 的 `./src/agent/graph.py:graph` 永远不用改。

- `iris-agent-platform/src/agent/nodes/__init__.py`（**新建**）
- `iris-agent-platform/src/agent/nodes/triage.py`（**新建**：triage 节点）
- `iris-agent-platform/src/agent/nodes/investigate.py`、`verify.py`、`report.py`（**搬迁**：M4 写在 `graph.py` 里的三个节点原样搬过来，逻辑不动）
- `iris-agent-platform/src/agent/state.py`（**改造**：新增 `Severity` / `Domain` / `TriageResult`，把 `IncidentState.triage` 的类型从 `dict | None` 收紧成 `TriageResult | None`）
- `iris-agent-platform/src/agent/graph.py`（**改造**：只剩装配，节点实现全部 import 自 `nodes/`；把 triage 接在 investigate 之前）

---

## 任务 T6.1 · 拆 nodes 子包 + triage 结构化输出 + 解析失败降级

**id**：T6.1 ｜ **所属模块**：M6 ｜ **优先级**：P0

**一句话目标**：把"一堆真实告警"变成一个结构化的分诊结果，且永不因模型解析失败而卡死。

**前置知识（≤2 新概念）**
- **结构化输出 `with_structured_output(Pydantic)`**
  1. 让 LLM 直接产出符合 Pydantic 模型的对象，而不是自由文本再正则解析。
  2. 底层是把 Pydantic schema 转成 tool/JSON schema 交给模型，所以**模型必须支持工具调用或 JSON 模式**——DashScope 与 OpenRouter 各家族能力不同，先确认再用。
  3. 它**不保证一定成功**：模型可能返回不在枚举里的值或缺字段，抛的是 Pydantic `ValidationError`，必须自己接住（这就是第 2 段的降级）。
  4. *Java 类比*：像让接口把响应反序列化进一个 DTO，schema 就是契约；但对方是个不太守规矩的第三方，校验失败要有兜底。
  5. 📖 https://docs.langchain.com/oss/python/langchain/structured-output （需核实锚点）· video-2 P85-88「输出解析和结构化」
- **疑似域（Domain）作为下游路由标签**
  1. triage 判出的疑似域不是给人看的，是给 **M11 的 Skills 预筛**当路由键用的。
  2. 所以枚举值必须和 M11 的映射表**逐字一致**（`db` 不能一边写成 `database`），否则预筛静默失灵——查都查不出来。
  3. 判不准时宁可多列几个域（宁滥勿缺），漏掉才是真损失。
  4. *Java 类比*：给下游打的路由标签，标签值是两边的硬契约。

### 🟡 第 1 段 · 改造 A（先把节点拆出来，再加新节点）

M4 为了让条件边跑起来，把 `investigate_once`/`verify`/`report` 三个节点都写在了 `graph.py` 里。现在要加第四个节点，先把它们拆出去，否则 `graph.py` 会越滚越大：

1. 建 `src/agent/nodes/`，把三个节点函数**原样**搬进 `investigate.py`/`verify.py`/`report.py`，逻辑一行不改；
2. `graph.py` 只保留装配：`build_graph()` 里 import 节点、`add_node`、`add_conditional_edges`；
3. **先跑一次 webhook 确认拆完还是通的**，再动新代码——把"搬家"和"加功能"分成两次可回滚的改动。

### 🟡 第 2 段 · 改造 B（写 triage 节点）

先在 `state.py` 里补上本模块负责的三个模型：

```python
class Severity(StrEnum):
    """事故严重度，由 triage 判定；注意它和 Alertmanager labels.severity 不是一回事——
    AM 的是告警规则作者预设的静态标签，这个是模型看完整组告警后给的判断."""

    P0 = "P0"  # 核心链路不可用，用户大面积受影响
    P1 = "P1"  # 核心功能明显劣化（错误率/时延突破阈值），仍可部分服务
    P2 = "P2"  # 局部劣化或非核心链路，默认降级值也是它
    P3 = "P3"  # 观察级，暂不影响用户


class Domain(StrEnum):
    """疑似根因所属的技术域。

    ⚠ 这 7 个字面值是与 M11 Skills 预筛映射表的硬契约，改任何一个都要同步改 M11 的
    loader.listing_for()，否则预筛会静默返回空列表。
    """

    DB = "db"                  # 数据库层：慢查询、锁等待、连接池打满
    THREADPOOL = "threadpool"  # 线程池/下游超时：池耗尽、队列堆积
    JVM = "jvm"                # JVM 层：GC 压力、堆内存、类加载
    RESOURCE = "resource"      # 宿主资源：CPU/磁盘/网络饱和
    CONTAINER = "container"    # 容器/实例：宕掉、CrashLoop、副本数不足
    CONFIG = "config"          # 配置变更：Nacos 发布、开关误改
    CACHE = "cache"            # 缓存：Redis 不可用、击穿/雪崩


class TriageResult(BaseModel):
    """轻模型对一组告警做的初步分诊结果，由 M6 的 triage 节点写入 IncidentState.triage."""

    severity: Severity                # 事故严重度，降级默认值为 P2
    suspected_domains: list[Domain]   # 疑似技术域，可多选；降级默认值为全部 7 个（宁滥勿缺）
    # fast_path / candidate_root 两个字段在 M11 补——它们依赖 matched_cases，
    # 而 matched_cases 要等 M11 的 Chroma 检索才有真实来源。
```

再把 `IncidentState.triage` 的类型从 M4 的占位 `dict | None` 收紧成 `TriageResult | None`。

然后写 `nodes/triage.py`：
1. **输入**：`state["alerts_ref"]` 指向的 `alert.json`（M5 落的原始告警）里读出 `alertname`/`service`/`labels`/`annotations`——读盘不读 state，因为 state 里只有引用；
2. **prompt**：告警列表摘要 + 7 个 Domain 的字面值和各自含义（**把枚举含义写进 prompt**，别指望模型从字段名猜）；
3. **调用**：`get_model(AgentRole.TRIAGE).with_structured_output(TriageResult)`；
4. **输出**：`{"triage": result}` 写回 state；
5. **接线**：`START → triage → investigate_once → verify → ...`，triage 排在 investigate 前面（M4 的条件边不动）。

### 🔴 第 3 段 · 自研（解析失败的两级降级）

**从空文件**写降级逻辑：
1. `with_structured_output` 抛 `ValidationError` / 返回 None → **重试 1 次**（第二次 prompt 里追加一句"上次输出不合法，请严格只输出 schema 要求的字段"）；
2. 再失败 → 返回**降级默认值**并在 state 里留痕：

```python
TRIAGE_FALLBACK = TriageResult(
    severity=Severity.P2,              # 不敢判高也不敢判低，取中间
    suspected_domains=list(Domain),    # 全部 7 个域全开 = 全面排查，保证不漏
)
```
3. 降级时把 `degraded_reason="triage_fallback"` 一并写回 state——M8 的报告里要能看出"这次分诊是兜底来的"，不能静默；
4. **不许因为分诊失败就中断流程**：triage 是加速器不是守门员，它挂了后面照样得查。

**落笔顺序**
1. 建 `nodes/` 子包，把 M4 的三个节点原样搬进去，跑一次 webhook 确认没搬坏。
2. 在 `state.py` 写 `Severity`/`Domain`/`TriageResult` 三个模型。
3. 写 `nodes/triage.py`，先只 return 一个写死的 `TriageResult`，把节点接进图跑通。
4. 换成真实 `with_structured_output` 调用，用某个故障的真实告警 fixture 打一次，看 `suspected_domains` 判得对不对。
5. 自研降级：把 prompt 故意改坏（比如让它输出一段散文），验证重试 1 次后走默认值、流程继续。
6. 把 prompt 改回去，确认正常路径没被降级逻辑影响。

**关键提示（≤3）**
- 模型经**参数**传进节点（`triage_node(state, model)`），别在节点内部直接 `get_model()`——M12 的消融开关和临时换模型都靠这个注入点。
- `Domain` 的 7 个字面值与 M11 的预筛映射表是硬契约，写完在两边各 grep 一次核对。
- 降级默认值必须是"全面排查"（疑似域全开），不能是"空列表"——空列表会让 M11 的预筛什么 Skill 都不给，等于比没有分诊还糟。

**卡住降级路径（30 分钟没思路）**
- 结构化输出老失败：先确认该模型支持 tool/JSON 模式；再把枚举的**含义**写进 prompt（只给字段名模型猜不出 `threadpool` 和 `jvm` 的边界）。
- 严重度总是判 P0：prompt 里给出每一级的判定标准（影响面 + 是否可服务），别只给枚举名。
- 视频：video-2 P86-87「输出解析和结构化」逐步讲解；video-1 P42-44「Pydantic 高级特性」兜底。
- 问 AI：「DashScope qwen 用 `with_structured_output(TriageResult)`，severity 字段老是返回不在枚举里的值，我的 Pydantic 定义和 prompt 这样（贴出），怎么约束它？」

**真实 HTTP 验收（含异常路径）**
```bash
cd iris-agent-platform
uv run uvicorn agent.app:app --port 8000

# 正常路径：用某个真实故障（如 pm-question 慢查询）的告警 fixture
curl -s -XPOST localhost:8000/webhook/alertmanager -d @slow-query-alert.json
# 从 checkpointer 里捞这次事故的 state，看 triage 字段
sqlite3 runs/checkpoints.db \
  "select checkpoint from checkpoints where thread_id='inc-YYYYMMDD-001' order by rowid desc limit 1;"
# 期望：triage.suspected_domains 含 "db"；triage.severity 落在 P0~P3 里
# （state 不好读时，改成在 triage 节点里打一行结构化日志核对，别为了看字段去写临时脚本）

# 异常路径：喂一段畸形/与任何服务无关的告警
curl -s -XPOST localhost:8000/webhook/alertmanager -d @garbage-alert.json
# 期望：重试 1 次后走降级 → severity=P2、suspected_domains 全部 7 个、
#       degraded_reason="triage_fallback"，且流程继续走到 investigate，不卡死
```
判定特征：真实告警分诊出正确疑似域；畸形输入走默认值且留痕；流程在任何情况下都不因 triage 失败中断。依赖真实服务：DashScope（triage 轻模型）、M5 的 webhook 入口。

**完成判定**
- [ ] `nodes/` 子包建好，M4 的三个节点原样搬入，`graph.py` 只剩装配，`langgraph.json` 未改
- [ ] `Severity`（4 值）/ `Domain`（7 值）/ `TriageResult`（2 字段）定义在 `state.py`，且**没有**提前写 `fast_path`/`candidate_root`
- [ ] `IncidentState.triage` 类型已从 `dict | None` 收紧为 `TriageResult | None`
- [ ] 真实告警分诊出正确 `suspected_domains`
- [ ] 解析失败重试 1 次 → 再失败走全域默认值，`degraded_reason="triage_fallback"` 留痕
- [ ] 模型是参数注入而非节点内 `get_model()`
- [ ] `Domain` 的 7 个字面值已与 M11 预筛映射表约定一致（M11 落地时再 grep 复核）

---
*版本 C 特征：分诊全靠"在真实工程上先拆 nodes 子包 → 改造出第一个 LLM 节点 → 自研两级降级"长出来，第一次把 LLM 的输出变成可靠契约，同时把只有以后才有数据源的快慢路径留给 M11。*
