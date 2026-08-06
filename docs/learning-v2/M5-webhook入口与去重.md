# 模块 5 · 自建 FastAPI 入口 + webhook 去重 + checkpointer — 版本 C

> 编排理念：🟡在本项目真实代码上改造（`langgraph dev` 托管 → 自建 uvicorn 入口；无状态 run → 带 checkpointer 的续跑）→ 🔴自研指纹去重、告警落盘与死信兜底。**这是整个系统唯一的入口，也是运行方式的分水岭。**

**本模块的能力边界（运行方式在这里换挡，先对齐）**
- **端口从 2024 换到 8000**：M3/M4 靠 `langgraph dev`（LangGraph Server 托管，端口 **2024**）触发图。本模块起，真实入口是**自建 FastAPI + uvicorn（端口 8000）**——因为 Alertmanager 只会往你给它的那个 URL POST 一份**它自己格式**的 payload，Server 自带的 `/runs` 接口没法直接收。M6 之后所有验收命令一律用 8000。
- **`langgraph.json` 不删**：它继续给 LangGraph Studio 做可视化调试用。做法见 T5.2——图的构建改成工厂函数，Studio 走无 checkpointer 的那份，uvicorn 走带 `AsyncSqliteSaver` 的那份。
- **checkpointer 落在这里而不是 M4**：M4 时项目还跑在 LangGraph Server 托管模式下，persistence 由 Server 自己管，给 `compile(checkpointer=...)` 传自定义 checkpointer 会直接报错。有了自建 uvicorn 才轮得到自己管持久化，`thread_id = incident_id` 也才有真正的外部来源（webhook 生成的 incident_id）。
- **工具集不变**：仍只有 `query_metrics` 一个真实接入，其余 6 个在 **M7** 接入。
- **本模块产出被后面复用**：`incident_dir()` 的白名单校验是 M7 `EvidenceStore` 和 M8 报告落盘的共同地基；checkpointer 是 M10（内层 resume）和 M12（审批挂起 resume）的前提。

## 学完本模块你能做到的 3 件事
1. 自建一个 FastAPI 应用做整个系统的入口，解析真实 Alertmanager webhook 负载，抽出 alertname/service/labels 并把原始告警落盘。
2. 用告警指纹在 30 分钟窗口内去重——同一场事故的重复告警不会重复启动诊断；并用 `AsyncSqliteSaver` + `thread_id=incident_id` 让图 kill 进程后能续跑。
3. 坏负载不丢、不让 Alertmanager 重试风暴：落死信文件并返回 200。

## 本模块交付物清单（文件路径级）

> 目录仍保持扁平：`state.py`/`graph.py`/`guard.py` 留在 `src/agent/` 顶层，本模块新增的也都放顶层。`langgraph.json` 的入口声明不动。

- `iris-agent-platform/src/agent/app.py`（**新建**：FastAPI 应用 + `POST /webhook/alertmanager` + lifespan 管 checkpointer/后台任务）
- `iris-agent-platform/src/agent/alerts.py`（**新建**：`Alert` 模型 + `parse_am_payload` + `fingerprint_of` + `new_incident_id`）
- `iris-agent-platform/src/agent/paths.py`（**新建**：`incident_dir()`（白名单校验防路径穿越）+ `write_alert_fixture()`）
- `iris-agent-platform/src/agent/store.py`（**新建**：SQLite incident 表 + `find_active` + `merge_alert` + `mark_failed`）
- `iris-agent-platform/src/agent/graph.py`（**改造**：`graph = ...` 改成 `build_graph(checkpointer=None)` 工厂 + 保留模块级 `graph` 供 Studio）
- `runs/checkpoints.db`、`runs/incidents/{id}/alert.json`、`runs/deadletter/*.json`（运行时产物）

---

## 任务 T5.1 · 自建 FastAPI 入口 + 解析 webhook + 指纹去重 + 告警落盘

**id**：T5.1 ｜ **所属模块**：M5 ｜ **优先级**：P0

**一句话目标**：从 `langgraph dev` 换挡到自建 uvicorn，把 Alertmanager 的 JSON 解析成 Alert 列表，按指纹在 30 分钟内去重，并把原始告警落盘。

**前置知识（≤2 新概念）**
- **Alertmanager webhook 格式 + 自带 fingerprint**
  1. AM v4 负载顶层：`version`/`groupKey`/`status`/`receiver`/`commonLabels`/`alerts[]`。
  2. 每条 alert 的关键路径：`alerts[*].labels.alertname`、`alerts[*].labels.service`、`alerts[*].labels.severity`、`alerts[*].status`、`alerts[*].startsAt`、`alerts[*].annotations`。
  3. **每条 alert 自带 `alerts[*].fingerprint`**——去重直接用它，不用自己算 hash。
  4. *Java 类比*：像收支付回调，`fingerprint` 就是天然的幂等键。
  5. 📖 https://prometheus.io/docs/alerting/latest/configuration/#webhook_config （webhook 负载，需核实锚点）
- **不可信输入 → 路径穿越防护**
  1. `incident_id` 由本系统生成，但 payload 里的字段（service/alertname）来自外部，任何用它们拼路径的地方都要先过白名单。
  2. 校验做两层：①字符白名单正则；②`Path.resolve()` 后确认落在 `runs/incidents/` 之内。
  3. *Java 类比*：文件上传时校验落地路径必须在白名单目录内，防目录穿越漏洞。
  4. 📖 https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html （路径穿越防护，需核实锚点）

### 🟡 第 1 段 · 改造（把入口从 langgraph dev 换成自建 FastAPI）

1. 新建 `src/agent/app.py`，先只放一个 `POST /webhook/alertmanager`，**收到就把整个 payload `json.dumps` 打出来再返回 200**——先用一份**真实**的 AM 负载（M1 注入故障时从 Alertmanager 抓的那份，不要用文档里的示例）把字段路径认准。
2. `uv run uvicorn agent.app:app --port 8000 --reload` 起服务，把 M1 的 Alertmanager 的 webhook 地址指过来（或直接 `curl -d @am-fixture.json`），确认真实负载能打进来。
3. 再写 `alerts.py` 把打印换成解析：

```python
class Alert(BaseModel):
    """一条 Alertmanager 告警，字段全部来自 AM v4 负载，不做二次加工."""

    alertname: str        # labels.alertname，告警规则名（M1 的 alert-rules.yaml 里定义）
    service: str          # labels.service，告警指向的服务名，对齐 prunus-mume 真实 application 值 [R3]
    severity: str         # labels.severity，AM 侧的严重度标签，注意它不等于 M6 triage 判出来的 severity
    status: str           # "firing" | "resolved"
    starts_at: datetime   # startsAt，告警开始时间
    labels: dict[str, str]       # 完整 labels 原样保留，M6 的 triage prompt 会用到
    annotations: dict[str, str]  # summary/description 等，M6 的 triage prompt 会用到
    fingerprint: str      # AM 自带的 fingerprint，去重的第一优先来源


def parse_am_payload(payload: dict) -> list[Alert]:
    """把 AM v4 负载解析成 Alert 列表；任一必需字段缺失就抛，由 T5.3 的死信兜底接住."""


def fingerprint_of(alerts: list[Alert]) -> str:
    """取本组告警的指纹：
    1. 优先用 alerts[0].fingerprint（AM 自带，稳定）；
    2. 缺失时回退自算 sha256(alertname + service + 排序后的关键 labels)[:16]；
    3. 回退算法里**不许**掺入 starts_at 等时间类 label，否则每条都算"不同"永远去不掉重。
    """


def new_incident_id() -> str:
    """生成 inc-YYYYMMDD-NNN 形式的事故 id；NNN 是当天序号，从 incident 表当天最大值 +1。
    这个 id 同时是：网关 X-Incident-Id、落盘目录名、以及 T5.2 的 checkpointer thread_id。"""
```

### 🔴 第 2 段 · 自研（incident 存储 + 告警落盘）

**从空文件**写 `store.py`（SQLite，不引 ORM）：

```sql
CREATE TABLE IF NOT EXISTS incident (
    incident_id TEXT PRIMARY KEY,  -- inc-YYYYMMDD-NNN
    fingerprint TEXT NOT NULL,     -- 去重键，建索引
    status      TEXT NOT NULL,     -- open | running | closed | failed
    created_at  REAL NOT NULL,     -- epoch 秒
    updated_at  REAL NOT NULL,     -- 后台任务每次推进状态时更新
    alerts_ref  TEXT,              -- runs/incidents/{id}/alert.json 的路径，不存 payload 全文
    last_error  TEXT               -- 后台图执行异常时落这里，T5.3 用（不许让异常只活在日志里）
);
```

- `find_active(fingerprint: str, within_seconds: int = 1800) -> str | None`：查 30 分钟内同指纹且 `status in ('open','running')` 的 incident_id。
- `create_incident(incident_id, fingerprint, alerts_ref) -> None`
- `merge_alert(incident_id: str, alert: Alert) -> None`：命中去重时把这条告警并进已有事故（追加进 alert.json 的 `alerts[]`，更新 `updated_at`）。
- `mark_status(incident_id, status, last_error=None) -> None`

**从空文件**写 `paths.py`：

```python
RUNS_ROOT = Path("runs/incidents").resolve()
_INCIDENT_ID_RE = re.compile(r"^inc-\d{8}-\d{3}$")


def incident_dir(incident_id: str) -> Path:
    """返回 runs/incidents/{incident_id}/，不存在则创建.

    incident_id 虽然是本系统生成的，但它会随 state 流转、也会从 HTTP path 参数
    （M8 的 GET /incidents/{id}）回到这里，属于不可信输入，两层校验都要有：
    1. 先用 _INCIDENT_ID_RE 白名单校验形状（只允许 inc-YYYYMMDD-NNN）；
    2. 再 resolve() 后确认结果目录确实在 RUNS_ROOT 之内，否则抛 ValueError。
    M7 的 EvidenceStore、M8 的报告落盘、M9 的 verdict 落盘都复用这一个函数，不要各写一份。
    """


def write_alert_fixture(incident_id: str, alert_payload: dict) -> str:
    """把 Alertmanager 原始 payload 原样落盘成 runs/incidents/{id}/alert.json，
    返回相对仓库根的路径字符串，写进 IncidentState.alerts_ref。

    落的是原样 payload 不是解析后的 Alert 列表——出问题时要能拿原文复现；
    state 里只存这个返回值，不存 payload 全文（M4 定的 [R13] 引用式字段约定）。
    """
```

**落笔顺序**
1. 建 `app.py`，只回显 payload，`uvicorn --port 8000` 起来，用真实 AM 负载打通一次。
2. 写 `alerts.py` 的 `Alert` + `parse_am_payload`，把回显换成解析结果。
3. 写 `fingerprint_of`，先只走"AM 自带字段"分支。
4. 自研 `store.py`：建表 + `create_incident` + `find_active`。
5. 连续打两次同一份 fixture，第二次应返回 `{"deduped": true}` 且不新建行。
6. 自研 `paths.py`，把原始 payload 落盘并把路径写进 `alerts_ref`；顺手用 `inc-../../etc` 这种 id 试一次，确认被拒。
7. 补 `fingerprint_of` 的回退分支（删掉 fixture 里的 `fingerprint` 字段验证它）。

**关键提示（≤3）**
- 指纹别把时间戳类 label 算进去，否则每条都"不同"，永远去不掉重。
- `incident_id` 就是 T5.2 的 `thread_id`，也是网关的 `X-Incident-Id`，三处必须是同一个值——起名和生成只留一个来源（`new_incident_id()`）。
- 落盘只落原始 payload，`state` 里只存路径；这是 M4 定的引用式字段约定，从这里开始真正执行。

**卡住降级路径（30 分钟没思路）**
- 字段取不到：把整个 payload `json.dumps(indent=2)` 打出来对字段路径，别照文档猜结构（AM 版本之间有差异）。
- 去不掉重：把两次的 `fingerprint_of` 结果和 `find_active` 的 SQL 参数一起打出来，先确认是"指纹不同"还是"时间窗判错"。
- 视频：webhook 解析无对口视频，以 Alertmanager 官方 webhook 文档为主。
- 问 AI：「这是一份真实 Alertmanager webhook JSON（贴出），我要抽 alertname/service 并用自带 fingerprint 去重，Pydantic 模型和 fingerprint 函数怎么写？」

**真实 HTTP 验收（含异常路径）**
```bash
cd iris-agent-platform
uv run uvicorn agent.app:app --port 8000 --reload

curl -s -XPOST localhost:8000/webhook/alertmanager \
  -H 'Content-Type: application/json' -d @am-fixture.json    # 200，incident 表落一行
sqlite3 runs/incidents.db "select incident_id,fingerprint,status,alerts_ref from incident;"
cat runs/incidents/inc-*/alert.json                          # 原始 payload 原样落盘

curl -s -XPOST localhost:8000/webhook/alertmanager \
  -H 'Content-Type: application/json' -d @am-fixture.json    # 30min 内同指纹 → {"deduped":true}
sqlite3 runs/incidents.db "select count(*) from incident;"   # 仍是 1

# 异常路径 ①：改 fixture 的 startsAt 但保持核心 label 不变 → 仍去重（指纹对时间不敏感）
# 异常路径 ②：把 fixture 里的 fingerprint 字段删掉 → 走自算回退分支，两次仍能去重
```
判定特征：首次建 incident 且原始告警落盘；30 分钟内同指纹 deduped、31 分钟后新建；删掉 AM fingerprint 后回退算法仍能去重。依赖真实服务：无外部依赖（但 fixture 必须是 M1 抓的真实告警，不是手编的）。

**完成判定**
- [ ] `uvicorn agent.app:app --port 8000` 能起，真实 AM 负载能解析出正确字段
- [ ] `fingerprint_of` 优先用 AM 自带字段，回退算法不含时间类 label
- [ ] 30 分钟内同指纹去重（`merge_alert` 并入已有事故）、31 分钟后新建
- [ ] `incident` 表 7 列齐全，`alerts_ref` 存路径而非 payload 全文
- [ ] `incident_dir()` 的白名单 + `resolve()` 两层校验都在，畸形 id 被拒
- [ ] `incident_id` 只有 `new_incident_id()` 一个生成来源

---

## 任务 T5.2 · 异步启图 + checkpointer 断点续跑

**id**：T5.2 ｜ **所属模块**：M5 ｜ **优先级**：P0

**一句话目标**：新事故异步启动诊断图，并用 `AsyncSqliteSaver` + `thread_id=incident_id` 让进程 kill 掉重启还能从上次的节点接着走。

**前置知识（≤2 新概念）**
- **checkpointer（AsyncSqliteSaver）**
  1. 每个 superstep 结束时把整个 state 存盘，`thread_id` 标识一次会话，可从任意节点恢复。
  2. 恢复的调用方式是**同一个 `thread_id` 再 `ainvoke` 一次**，input 传 `None` 表示"从上次断点继续"而不是重跑。
  3. 自建 FastAPI 是异步的，所以用 `AsyncSqliteSaver` 而不是同步的 `SqliteSaver`。
  4. *Java 类比*：把工作流实例状态持久化到库，重启后按实例 id 捞回来接着跑。
  5. 📖 https://docs.langchain.com/oss/python/langgraph （persistence/checkpointer，需核实锚点）· video-2 P44-47「记忆存储·短期·Postgres」
- **异步后台启图 + 生命周期托管**
  1. webhook 收到后不能阻塞等诊断跑完（AM 会超时重试），要异步触发图并立刻回 200。
  2. 但**不能裸 `asyncio.create_task` 然后丢掉引用**——异常会被静默吞掉，且进程退出时任务被硬砍。
  3. 任务的异常必须落到 `incident.last_error` 可查，不能只活在日志里。
  4. *Java 类比*：`@Async` 投线程池后立即返回，但线程池要受容器生命周期管理、异常要落库。
  5. 📖 https://docs.langchain.com/oss/python/langgraph （ainvoke，需核实锚点）· video-2 P66「异步+并发执行工具类」

### 🟡 第 1 段 · 改造（把图的编译改成工厂，两种入口各拿一份）

问题：`langgraph.json` 指着 `graph.py:graph`，Studio 需要一个**模块级、已编译、不带自定义 checkpointer** 的图；而 uvicorn 需要一个**带 `AsyncSqliteSaver`** 的图。两个需求不能塞进同一个模块级变量。

解法是把编译过程抽成工厂，两边各取所需——`langgraph.json` 一个字都不用改：

```python
def build_graph(checkpointer=None):
    """装配节点与边并编译。checkpointer=None 时交给托管方自己管 persistence."""
    workflow = StateGraph(IncidentState)
    ...  # M4 已经写好的节点注册 + 条件边，原样搬进来
    return workflow.compile(checkpointer=checkpointer)


graph = build_graph()   # 供 langgraph.json / LangGraph Studio 可视化调试用
```

FastAPI 侧在 lifespan 里建带 checkpointer 的那一份：

```python
@asynccontextmanager
async def lifespan(app: FastAPI):
    async with AsyncSqliteSaver.from_conn_string("runs/checkpoints.db") as saver:
        app.state.graph = build_graph(checkpointer=saver)
        app.state.tasks = set()          # 持有后台任务引用，防被 GC
        yield
        # 退出：停收新任务 → 等在途任务收尾（超时就 cancel）
        await _drain(app.state.tasks, timeout=30)
```

启图时把 `thread_id` 绑成 incident_id：

```python
config = {"configurable": {"thread_id": incident_id}}
await app.state.graph.ainvoke(initial_state, config)
```

### 🔴 第 2 段 · 自研（后台任务托管 + 异常落库）

**从空文件**写后台执行的那一层：
1. `spawn_incident_run(app, incident_id, initial_state)`：创建 task、**存进 `app.state.tasks`**、加 `add_done_callback` 移除引用；
2. task 内部包 try/except：成功 → `mark_status(incident_id, "closed")`；异常 → `mark_status(incident_id, "failed", last_error=traceback)`，绝不让异常只进日志；
3. `resume_incident_run(app, incident_id)`：用同一个 `thread_id`、input 传 `None` 再 `ainvoke` 一次，用于进程重启后手工续跑（M12 的 `copilot approve` 走的也是这条路）。

**落笔顺序**
1. 先在 webhook 里**同步** `await graph.ainvoke(...)` 跑通一次（图能从入口走到 report），确认接线对。
2. 改成工厂 `build_graph(checkpointer=...)`，模块级 `graph` 保留，`langgraph dev` 仍能起（证明没把 Studio 弄坏）。
3. 接 `AsyncSqliteSaver` + lifespan，跑一次，确认 `runs/checkpoints.db` 有该 thread 的多步记录。
4. 改成异步后台执行，webhook 立即返回 200。
5. 自研异常落库：故意把网关 token 改错，看 `incident.last_error` 有没有内容。
6. 验证断点续跑：图跑到 investigate 时 kill uvicorn，重启后调 resume，确认从上次节点继续而不是从头跑。

**关键提示（≤3）**
- `thread_id` 必须等于 `incident_id`：不一致会导致"恢复"实际上开了一条新会话，从头跑一遍，还会把网关预算再花一遍。
- 别裸 `create_task` 丢引用——Python 只对 task 持弱引用，丢了引用可能被 GC 掉，异常也会被静默吞。
- `langgraph dev` 和 uvicorn 不要同时跑同一份 `runs/checkpoints.db`：SQLite 并发写会锁；调试时二选一。

**卡住降级路径（30 分钟没思路）**
- 恢复不了、每次都从头跑：确认两次调用 `thread_id` 一致，且恢复那次 input 传的是 `None` 而不是完整 state（传完整 state 等于覆盖，会从头开始）。
- `compile(checkpointer=...)` 报错：确认你现在跑的是自建 uvicorn，不是 `langgraph dev`——托管模式下不允许传自定义 checkpointer。
- 后台任务悄悄失败：先确认 `add_done_callback` 里读了 `task.exception()`，否则异常永远不会浮出来。
- 视频：video-2 P45-46「短期记忆案例·Postgres 短期存储」对口讲解。
- 问 AI：「LangGraph 用 AsyncSqliteSaver，我 kill 进程重启后同一 thread_id 却从头跑，我的 compile、config 和 resume 调用这样写（贴出），哪里错了？」

**真实 HTTP 验收（含异常路径）**
```bash
uv run uvicorn agent.app:app --port 8000

curl -s -XPOST localhost:8000/webhook/alertmanager -d @am-fixture.json   # 200，立即返回
sqlite3 runs/incidents.db "select incident_id,status from incident;"     # status 推进到 running
sqlite3 runs/checkpoints.db \
  "select count(*) from checkpoints where thread_id='inc-YYYYMMDD-001';" # >1（多步都存了）

# 断点续跑：趁图还在 investigate 时 kill uvicorn，重启后 resume
kill %1 && uv run uvicorn agent.app:app --port 8000 &
curl -s -XPOST localhost:8000/incidents/inc-YYYYMMDD-001/resume          # 从上次节点继续
# 期望：日志里 investigate 之前的节点不再重跑；网关审计里也没有重复的 query_metrics 调用

# 异常路径 ①：用一个没跑过的 thread_id 去 resume → 从头开始（证明记忆按 incident 隔离）
# 异常路径 ②：把 GATEWAY_BEARER_TOKEN 改错 → 后台任务异常，incident.status='failed' 且 last_error 有栈
sqlite3 runs/incidents.db "select status,last_error from incident order by created_at desc limit 1;"
```
判定特征：`checkpoints.db` 有该 thread 的多步记录；kill-重启后同 incident 续跑、不重复调网关；后台异常落 `last_error` 可查。依赖真实服务：M2 网关、Prometheus、investigate 模型。

**完成判定**
- [ ] `build_graph(checkpointer=None)` 工厂成型，模块级 `graph` 保留，`langgraph dev` 仍能起
- [ ] uvicorn 侧用 `AsyncSqliteSaver`，`thread_id == incident_id`
- [ ] webhook 立即返回 200，图在后台跑
- [ ] 后台任务引用被持有，异常落 `incident.last_error`
- [ ] kill 进程重启后同 incident 能从上次节点续跑，且不重复消耗网关预算
- [ ] 异 thread_id 恢复 → 从头开始（记忆按 incident 隔离）

---

## 任务 T5.3 · 死信兜底 + 优雅退出

**id**：T5.3 ｜ **所属模块**：M5 ｜ **优先级**：P0

**一句话目标**：坏负载落死信文件并返回 200，进程退出时不硬砍在途事故。

**前置知识（≤1 新概念）**
- **死信（dead letter）**
  1. 解析失败的负载**原样**存盘不丢，返回 200 防 Alertmanager 重试风暴。
  2. 返回 200 是故意的：AM 对非 2xx 会按 `retry_interval` 反复重投，一份坏 payload 能把入口打爆。
  3. 存盘的是原始 bytes，不是解析到一半的中间结果——事后要能拿原文复现。
  4. *Java 类比*：MQ 消费失败进死信队列，消息不丢但也不阻塞正常消费。

### 🟡 第 1 段 · 改造（把解析异常从 500 变成 200 + 落盘）

1. 把 `parse_am_payload` 的调用包起来：任何 `ValidationError`/`KeyError`/`json` 解析异常 → 写 `runs/deadletter/<epoch_ms>-<uuid4hex[:8]>.json`，内容是**请求的原始 body**（`await request.body()`，不是 `await request.json()`——后者对非 JSON 直接就炸了）；
2. 死信文件旁边同名写一份 `.err` 记异常类型与栈，方便事后判断是格式变了还是数据脏；
3. 返回 `{"deadlettered": true, "file": "<path>"}` + HTTP 200。

### 🔴 第 2 段 · 自研（优雅退出）

**从空文件**写 lifespan 的退出段：
1. 置一个 `app.state.accepting = False`，之后进来的 webhook 直接 503（不再接新事故）；
2. `await asyncio.wait(app.state.tasks, timeout=30)`；
3. 超时仍未结束的任务 `cancel()`，并把这些 incident `mark_status(..., "failed", last_error="cancelled on shutdown")`——**有了 T5.2 的 checkpointer，这些事故重启后仍可 resume**，所以标 failed 不等于丢了。

**落笔顺序**
1. 打一份坏 payload（`{"bad":"payload"}`），确认现在是 500。
2. 加死信分支，再打一次，确认变成 200 且 `runs/deadletter/` 落了文件。
3. 打一份**非 JSON**的 body（`-d 'not-json'`），确认也能落死信而不是在 `request.json()` 处炸掉。
4. 自研优雅退出：起一次事故后立刻 Ctrl-C，观察日志里等待在途任务的过程。
5. 重启后对刚才被 cancel 的 incident 调 resume，确认能接着跑（与 T5.2 联动验证）。

**关键提示（≤3）**
- 用 `await request.body()` 拿原始 bytes 再自己 `json.loads`；直接 `await request.json()` 遇到非 JSON body 会先抛，死信分支根本轮不上。
- 坏负载返回 200 是故意的（防 AM 重试风暴），但必须落死信可追——"返回 200 且什么都没留"才是真丢数据。
- 退出时被 cancel 的事故不是丢了：checkpointer 已经存了断点，重启 resume 即可，`last_error` 里写清原因就行。

**卡住降级路径（30 分钟没思路）**
- 死信没落：确认异常捕获包住了 `json.loads` 和 Pydantic 校验两段，且 `except Exception` 兜底而不是只 catch 一种。
- Ctrl-C 直接就退了：确认后台任务真的被 `app.state.tasks` 持有，否则 lifespan 退出段等的是一个空集合。
- 视频：video-2 P66「异步+并发执行工具类」讲异步执行思路（可参考）。
- 问 AI：「FastAPI 的 lifespan 里，我想在退出时停收新请求、等待在途后台任务最多 30 秒再 cancel，正确的结构是什么？给结构不要整段实现。」

**真实 HTTP 验收（含异常路径）**
```bash
uv run uvicorn agent.app:app --port 8000

curl -s -XPOST localhost:8000/webhook/alertmanager -d '{"bad":"payload"}'   # 200 + {"deadlettered":true}
curl -s -XPOST localhost:8000/webhook/alertmanager -d 'not-json-at-all'     # 200，同样落死信
ls runs/deadletter/                        # 两份 .json + 两份 .err

# 优雅退出：起一次真实事故后立刻 Ctrl-C
curl -s -XPOST localhost:8000/webhook/alertmanager -d @am-fixture.json &
sleep 2 && kill -INT %1
# 期望：日志显示"停收新请求 → 等待 N 个在途任务"；超时的被 cancel 且 last_error 写明原因
# 重启后 resume 该 incident → 能接着跑（不是从头）
```
判定特征：坏负载（含非 JSON）一律 200 + 落死信；退出时在途任务被等待而非硬砍；被 cancel 的事故重启后可 resume。依赖真实服务：无（死信路径纯本地）。

**完成判定**
- [ ] 坏 payload 落 `runs/deadletter/` 并返回 200
- [ ] 非 JSON body 也能落死信（用的是 `request.body()` 不是 `request.json()`）
- [ ] 死信旁边有 `.err` 记异常类型与栈
- [ ] 退出时停收新请求、等待在途任务、超时才 cancel
- [ ] 被 cancel 的 incident `last_error` 写明原因，且重启后能 resume

---
*版本 C 特征：入口全靠"在真实工程上把托管入口改造成自建 FastAPI + checkpointer → 自研指纹去重、告警落盘与死信兜底"长出来；从这里开始，端口 8000 是整个系统唯一的门。*
