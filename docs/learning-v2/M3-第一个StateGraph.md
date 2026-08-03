# 模块 3 · 第一个 StateGraph（从 0 建 Python 工程 + 起手线性图）— 版本 C

> greenfield：忽略任何现有 Python 工程，**从空目录用 uv + LangGraph 官方 starter 起手**。🟢复刻官方 starter 跑通 → 🟡改造成接真实模型+工具 → 🔴自研工具薄封装。**这是你第一次真正碰 LangGraph。**

## 学完本模块你能做到的 3 件事
1. 从零建一个干净的 uv + LangGraph 工程，把 config/多模型路由接好，用真实 LLM（DashScope + OpenRouter）跑通一次结构化输出。
2. 说清 `StateGraph`、节点、边是什么，并让一个单节点图真实调用一次 query_metrics 拿到真数据。
3. 用 `get_model("triage"|"investigate"|"verify"|"report")` 按角色取到不同模型，为后面所有节点铺路。

## 本模块交付物清单（文件路径级）
- `agent-platform/`（**新建** uv 工程）：`pyproject.toml`、`langgraph.json`、`.env.example`
- `src/.../config.py`（Settings）、`src/.../models/router.py`（`get_model(role)` + `get_embeddings()`）
- `src/.../tools/client.py`（GatewayClient + Envelope）、`src/.../tools/definitions.py`（@tool）
- `src/.../graph.py`（单节点图调工具）、`scripts/smoke_models.py`

---

## 任务 T3.1 · 从 0 建工程 + config + 多模型路由

**id**：T3.1 ｜ **所属模块**：M3 ｜ **优先级**：P0

**一句话目标**：从空目录建出能按角色取真实模型、能读配置的 LangGraph 工程骨架。

**前置知识（≤2 新概念）**
- **LangGraph 工程结构（langgraph.json / graph.py）**：LangGraph 应用靠 `langgraph.json` 声明图入口，`graph.py` 导出编译好的图，`langgraph dev` 起本地服务自带 API。*Java 类比*：`langgraph.json` 像 `application.yml` 指定启动入口，`graph.py` 像 `@Configuration` 装配。📖 https://docs.langchain.com/oss/python/langgraph （工程结构/langgraph.json，需核实锚点）· video-2 P27-31「安装/启动 LangGraph 本地服务·调用 API」
- **多模型路由 get_model(role)**：不同节点用不同模型，集中在一个工厂按角色返回。*Java 类比*：一个 `ModelFactory`，按枚举返回不同 `@Bean`，型号/密钥全从配置注入。📖 https://docs.langchain.com/oss/python/langchain/models （需核实锚点）· video-2 P5-9「调用大模型·结构化输出」

### 🟢 第 1 段 · 复刻（用官方 starter 起一个空图并跑通）
- **读什么**：LangGraph 官方入门——`uv init` 建工程后，用 `langgraph new`（或 `pip install "langgraph-cli[inmem]"` 后的 create-app 模板）生成 starter，读它自带的 `langgraph.json` + `src/agent/graph.py`（那个返回固定值的单节点）。
- **跑什么**：`langgraph dev` 启动，让 starter 原样跑出它的默认返回——**先证明这套壳在你机器上能转**（复刻官方样例，一行不改）。

### 🟡 第 2 段 · 改造（接真实模型）
- 新建 `config.py`（`Settings(BaseSettings)`：网关 base_url/token、4 角色模型名、阈值）与 `models/router.py`（`get_model(role)` 走 **DashScope + OpenRouter 官方 integration**，型号/密钥全从 Settings 注入 [R5]）。
- `.env.example` 只列变量名 + 安全占位符，真 key 不入库。

### 🔴 第 3 段 · 自研（写冒烟脚本）
- **从空文件**写 `scripts/smoke_models.py`：对每个启用的 provider 各做一次真实 `invoke` + 一次 `with_structured_output`，打印 provider/model/耗时/PASS-FAIL，**不打印 key**。任一运行时角色未连通就不许进 T3.2。

**落笔顺序**
1. 复刻：`langgraph new` 生成 starter，`langgraph dev` 原样跑通。
2. 改造：写 config + router，`.env` 填真实 key/base_url/model。
3. 发一次请求看报错：跑 smoke_models，看哪个 provider 没连通。
4. 填逻辑：修 base_url/model 直到全 PASS。
5. 自研补全 smoke 脚本的结构化输出分支。

**关键提示（≤3）**
- 模型接入全走 langchain 官方 integration 包，**别自写 HTTP 客户端**。
- `.env` 真 key 不入库；`.env.example` 只放变量名。
- OpenRouter 一把 key 可代理多家族——verify 的"另一家族"就靠它（M9 用）。

**卡住降级路径（30 分钟没思路）**
- `langgraph dev` 起不来：确认 `langgraph-cli[inmem]` 装了、`langgraph.json` 的 graph 路径对。
- structured_output 报错：先确认该模型支持工具调用/JSON 模式（DashScope 与 OpenRouter 模型能力不同）。
- 视频：video-1 P16「init_chat_model」是最直接的模型初始化兜底讲解。
- 问 AI：「我用 `langgraph new` 建了工程，`langgraph dev` 报找不到 graph，我的 langgraph.json 这样（贴出），哪里错了？」

**真实 HTTP 验收（含异常路径）**
```bash
langgraph dev        # 官方 starter 原样起来（复刻段验收）
uv run python scripts/smoke_models.py     # 每个 provider：invoke + structured_output 均 PASS
# 异常路径：把 DASHSCOPE_API_KEY 改错一位 → 该 provider 打印 FAIL 且不泄露 key
```
判定特征：starter 能起；smoke 全 PASS（真实调用，非 mock）；错 key 时该 provider FAIL。依赖真实服务：DashScope、OpenRouter。

**完成判定**
- [ ] `langgraph new` starter 能原样跑通（复刻段）
- [ ] `get_model(role)` 四角色各返回正确的真实模型
- [ ] `smoke_models.py` 对 DashScope + OpenRouter 真实 invoke + 结构化输出全 PASS
- [ ] 工程是全新 uv 工程，未引用任何旧代码
- [ ] `.env.example` 只有变量名，无真实 key

---

## 任务 T3.2 · 第一个 StateGraph 单节点调真实工具

**id**：T3.2 ｜ **所属模块**：M3 ｜ **优先级**：P0

**一句话目标**：把 starter 的默认节点改成"真实调一次 query_metrics 并把结果放进 state"，理解 StateGraph 三要素。

**前置知识（≤2 新概念）**
- **StateGraph = 状态 + 节点 + 边**：状态（State）是贯穿整张图的"共享笔记本"——官方原话是 *the notebook your agent uses to keep track of everything it learns and decides as it works through the process*。节点是函数（读 state → 算 → 返回要更新的字段），边决定下一步去哪。挑字段时用官方给的两道判断题过一遍每个候选：①**要不要跨步骤保留？**要 → 放 state；②**能不能从别的数据算出来？**能 → 不存，用时现算。**另一条硬规则**：state 只存原始数据，不存格式化好的 prompt 文本——格式化放在节点内部做，这样不同节点能各自按需拼 prompt，State schema 也不会被某个 prompt 模板绑死，调试时也能看清每个节点到底拿到了什么原始数据。*Java 类比*：一个显式画出来的状态机/流程图（BPMN），节点是方法，state 是流转的上下文对象——但这个"上下文对象"要像 DTO 一样谨慎设计，别把 View 层拼好的字符串塞进 Domain 对象。📖 https://docs.langchain.com/oss/python/langgraph/thinking-in-langgraph#step-3-design-your-state （Step 3: Design your state）· video-2 P58-60「State&Reducer·节点&路由」
- **@tool + GatewayClient 薄封装**：`@tool` 装饰器把一个 Python 函数变成 LLM 能调的工具——**类型注解是必须的**（用来生成参数 schema），函数的 docstring 默认就是工具描述（告诉模型"什么时候该用这个工具"）。参数复杂时用 `@tool(args_schema=...)` 接一个 Pydantic `BaseModel`，每个字段配 `Field(description=...)`，模型靠这段描述判断怎么填参数。`config`/`runtime` 是保留参数名不能挪作业务用；要在工具里读图状态/中断上下文时用专门的 `ToolRuntime` 参数（对模型隐藏，不会出现在它看到的 schema 里）。*Java 类比*：给远程接口写一个 Feign client，`args_schema` 相当于把 `@RequestBody` 的 DTO 显式暴露给"调用方"（LLM）看，`Field(description=...)` 就是接口文档。📖 https://docs.langchain.com/oss/python/langchain/tools （@tool decorator · Advanced Schema Definition with Pydantic）· video-2 P12-16「智能体中定义工具」

### 🟢 第 1 段 · 复刻（读 starter 的图结构）
- **读什么**：starter `graph.py` 里 `StateGraph(State)` → `add_node(...)` → `add_edge(START, ...)` → `compile()` 的骨架。
- **跑什么**：原样跑一次，观察 state 怎么进、怎么出。

### 🟡 第 2 段 · 改造（把节点改成调工具）
- 先按前置知识里官方那"两道判断题"过一遍要放进 state 的字段，写出 `State`（对齐 `graph.py` 里已有的骨架，用 `@dataclass` 也可以换成 `TypedDict`/Pydantic `BaseModel`，三种官方都支持，本项目选 dataclass）：

```python
from dataclasses import dataclass, field


@dataclass
class State:
    # 输入：跨步骤都要用（M4 的 triage/investigate/verify/report 每个节点都读得到），且事后无法重新生成 → 必须存
    incident_id: str
    service: str

    # 工具调用的原始结果：存"数据"不存"格式化好的文本"
    # metrics_result 是 query_metrics 信封 data 字段的原样摘要（dict），不是拼给人看的一段话——
    # 要不要格式化成 prompt 文本，留给后面写 prompt 的节点自己决定
    metrics_result: dict | None = None

    # degraded 是 H3 的一等信号：网关降级时必须原样透传进 state，节点不能吞掉
    degraded: bool = False
    degraded_reason: str | None = None

    # 给 M4 状态机预留：后续每个节点调完工具都要能往这里追加一条证据
    evidence: list[dict] = field(default_factory=list)
```
- 字段为什么这么定：`incident_id`/`service` 是输入，没法从别的数据推导出来 → 必须存；`metrics_result` 只存 `query_metrics` 返回的原始摘要，**不要**在这一步就把它拼成给 LLM 看的一段 prompt——prompt 拼装放到后面写 prompt 的节点里，State 本身只认原始数据；`degraded`/`degraded_reason` 对应 M2 网关信封的同名字段，节点必须原样透传，不能因为图跑通了就悄悄丢掉降级信号；`evidence` 是给 M4 多节点状态机预留的追加型字段，本任务先把形状搭好，后面再挂 reducer（`Annotated[list, operator.add]` 之类）。
- 把默认节点改成 `investigate_once`：读 state 里的 `service` → 调 `query_metrics` 工具（经 GatewayClient）→ 把返回信封的 `data`/`degraded`/`degraded_reason` 分别写回 `State` 对应字段 → END。

### 🔴 第 3 段 · 自研（写 GatewayClient）
- **从空文件**写 `tools/client.py`：`GatewayClient(base_url,token,incident_id,agent_role).call(path,body)->Envelope`；429 抛 `BudgetExceededError`；其余 4xx 抛 `GatewayRequestError`。
- `tools/definitions.py` 用 `@tool` + Pydantic `args_schema` 包 M2 网关的 7 个工具（5 个模板工具 `query_metrics`/`query_logs`/`query_trace`/`query_cmdb`/`query_changes` + 2 个 raw 通道 `raw_promql`/`raw_logql`），degraded 原样透传。以 `query_metrics` 为例，参数要对齐 M2 `MetricsController` 的入参契约（`template/service/window/compareBaseline`）：

```python
from pydantic import BaseModel, Field
from langchain.tools import tool


class QueryMetricsInput(BaseModel):
    template: str = Field(description="指标模板名，如 error_rate/qps/p99/cpu/memory，一次只能查一个维度")
    service: str = Field(description="被查询的服务名，需与 CMDB 登记的服务名一致")
    window: str = Field(description="查询窗口，如 '30m'；先用大窗口看整体，再缩小窗口配合 end_offset_seconds 定位")
    compare_baseline: bool = Field(default=False, description="是否附带同比/环比基线用于对比")


def make_query_metrics(client: GatewayClient):
    @tool("query_metrics", args_schema=QueryMetricsInput)
    def query_metrics(template: str, service: str, window: str, compare_baseline: bool = False) -> dict:
        """按模板查询服务的时序监控指标（经网关代理 Prometheus query_range）。"""
        envelope = client.call("/api/v1/tools/query_metrics", {
            "template": template,
            "service": service,
            "window": window,
            "compareBaseline": compare_baseline,
        })
        return envelope.model_dump()  # data/degraded/degraded_reason 原样带出，节点里再分发进 State

    return query_metrics
```
- 其余 6 个工具（`query_logs`/`query_trace`/`query_cmdb`/`query_changes`/`raw_promql`/`raw_logql`）照同一套三件套：一个 Pydantic `args_schema` + 一个 `@tool` 包装函数 + 内部调 `GatewayClient.call`，参数字段照抄各自控制器的入参（见 M2 `docs/tool-contract.md`）。
- 工具函数里不吞异常：`GatewayRequestError`/`BudgetExceededError` 让它们往上抛，节点层再决定怎么写回 state（这样 M4 的路由逻辑才能看到真实错误类型，而不是一个被 catch 掉的空结果）。

**落笔顺序**
1. 复刻读 starter 图三要素。
2. 改造：节点先返回固定假值，让图从 START 走到 END 通。
3. 发一次请求看报错：`curl` 触发图 → 节点里 GatewayClient 还没写→报错。
4. 填逻辑：写 GatewayClient，节点真实调 query_metrics（打 M2 的网关）。
5. 再请求：看到 state 里出现真实指标数据。

**关键提示（≤3）**
- 先让"空节点跑通图"再填工具调用——别一步到位。
- degraded 是一等信号，必须从工具透传进 state（H3），不能吞掉。
- 这个单节点图是 M4 状态机的种子，state 字段起名要能复用。

**卡住降级路径（30 分钟没思路）**
- 图跑不动：先确认 `compile()` 成功、START/END 边接对；看 LangGraph 报的节点名。
- 视频：video-2 P25-26「LangGraph 介绍·Agent&WorkFlow」建立"图 vs Agent"的直觉。
- 问 AI：「我在 LangGraph starter 基础上把节点改成调一个 HTTP 工具，图能编译但节点拿不到 state 的 service 字段，我的 State 定义和节点签名这样（贴出），问题在哪？」

**真实 HTTP 验收（含异常路径）**
```bash
curl -XPOST localhost:8000/... -d '{"service":"<pm-svc>","template":"error_rate","window":"30m"}'
# 期望：返回体里看到来自真实 Prometheus 的指标数据（经 M2 网关的 query_metrics）
# 异常路径：把网关 token 改错 → 节点抛 GatewayRequestError，图优雅结束不崩、返回可读错误
```
判定特征：图调到真实 query_metrics 返回真数据；错 token 时优雅报错不崩。依赖真实服务：M2 网关、Prometheus。

**完成判定**
- [ ] starter 图能原样跑通（复刻段）
- [ ] 单节点图真实调用 query_metrics 并把结果写回 state
- [ ] `GatewayClient` 429→BudgetExceededError、degraded 透传
- [ ] 错 token 异常路径：图不崩、返回可读错误
- [ ] state 字段只存原始数据（不存格式化好的 prompt 文本），命名为 M4 状态机预留复用空间

---
*版本 C 特征：LangGraph 从 0 上手全靠"复刻官方 starter → 改造节点调真实工具 → 自研工具封装"，把陌生框架从空工程一层层长出来。*
