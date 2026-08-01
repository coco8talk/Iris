# 模块 5 · webhook 入口 + 指纹去重 — 版本 C

> 编排理念：🟢复刻真实 Alertmanager payload 解析 → 🟡改造成去重逻辑 → 🔴自研死信兜底。**这是整个系统唯一的入口。**

## 学完本模块你能做到的 3 件事
1. 解析真实 Alertmanager webhook 负载，抽出 alertname/service/labels。
2. 用告警指纹在 30 分钟窗口内去重——同一场事故的重复告警不会重复启动诊断。
3. 坏负载不丢、不让 Alertmanager 重试风暴：落死信文件并返回 200。

## 本模块交付物清单（文件路径级）
- `src/.../webhook/models.py`（Alert 模型）
- `src/.../webhook/ingest.py`（parse + fingerprint + new_incident_id）
- `src/.../store/incidents.py`（incident 表 + find_active）
- `src/.../webhook/router.py`（`POST /webhook/alertmanager`）

---

## 任务 T5.1 · 解析 webhook + 指纹去重

**id**：T5.1 ｜ **所属模块**：M5 ｜ **优先级**：P0

**一句话目标**：把 Alertmanager 的 JSON 解析成 Alert 列表，并按指纹在 30 分钟内去重。

**前置知识（≤2 新概念）**
- **Alertmanager webhook 格式 + 自带 fingerprint**：AM v4 负载里 `alerts[*].labels.alertname/service`，且每条 alert **自带 `fingerprint` 字段**，去重直接用它。*Java 类比*：像收支付回调，`fingerprint` 就是天然的幂等键，不用自己算 hash。📖 https://prometheus.io/docs/alerting/latest/configuration/#webhook_config （webhook 负载，需核实锚点）
- **指纹去重窗口**：30 分钟内同指纹且 open 的事故 → merge 不新建。*Java 类比*：像幂等表 + 时间窗，重复请求命中已有记录就合并。📖（自研；参考 Alertmanager 分组/抑制思想）

### 🟢 第 1 段 · 复刻
- **读什么**：一份真实 Alertmanager webhook JSON 样例（M1 注入故障时 debug 抓一份，或官方文档示例）。
- **跑什么**：写个最小解析把 `alerts[*].labels` 打印出来，先证明字段路径对。

### 🟡 第 2 段 · 改造
- `Alert(alertname,service,severity,status,starts_at,labels,annotations)`；`fingerprint(alerts)` **优先取 AM 自带字段**，回退才自算 `sha256(...)[:16]`；`new_incident_id()` = `inc-YYYYMMDD-NNN`。

### 🔴 第 3 段 · 自研
- **从空文件**写 `store/incidents.py`：SQLite `incident(incident_id PK,fingerprint,status,created_at,alerts_ref)`；`find_active(fingerprint, within=30min)->id|None`；`merge_alert(id, alert)`。

**落笔顺序**
1. 复刻：抓真实 AM JSON，解析打印字段。
2. 改造：写 Alert 模型 + fingerprint。
3. 发一次请求：`curl` 打 webhook fixture，看解析对不对。
4. 自研 incident 存储 + find_active。
5. 再请求两次同指纹，验证第二次 deduped。

**关键提示（≤3）**
- 指纹别把时间戳类 label 算进去，否则每条都"不同"永远去不掉重。
- 先用 AM 自带 fingerprint，稳定且省事。
- incident_id 与 M4 的 thread_id 是同一个，命名保持一致。

**卡住降级路径（30 分钟没思路）**
- 字段取不到：把整个 payload `json.dumps` 打出来对字段路径，别猜结构。
- 视频：webhook 解析无对口视频；以 Alertmanager 官方 webhook 文档为主。
- 问 AI：「这是一份 Alertmanager webhook JSON（贴出），我要抽 alertname/service 并用自带 fingerprint 去重，Pydantic 模型和 fingerprint 函数怎么写？」

**真实 HTTP 验收（含异常路径）**
```bash
curl -XPOST localhost:8000/webhook/alertmanager -d @am-fixture.json    # 200，incidents.db 落一行
curl -XPOST localhost:8000/webhook/alertmanager -d @am-fixture.json    # 30min 内同指纹 → {"deduped":true}
# 异常路径：改 fixture 的 starts_at 但保持核心 label 不变 → 仍去重（指纹对时间不敏感）
```
判定特征：首次建 incident；30 分钟内同指纹 deduped；指纹对时间戳不敏感。依赖真实服务：无（纯确定性，但可用 M1 真实告警做 fixture）。

**完成判定**
- [ ] 真实 AM JSON 能解析出正确字段（复刻段）
- [ ] fingerprint 优先用 AM 自带字段
- [ ] 30 分钟内同指纹去重、31 分钟后新建
- [ ] incident_id 与 M4 thread_id 命名一致

---

## 任务 T5.2 · 异步启图 + 死信兜底

**id**：T5.2 ｜ **所属模块**：M5 ｜ **优先级**：P0

**一句话目标**：新事故异步启动诊断图，坏负载落死信并返回 200。

**前置知识（≤2 新概念）**
- **异步后台启图**：webhook 收到后不能阻塞等诊断跑完，要异步触发图并立刻回 200。*Java 类比*：像 `@Async` 或投递到线程池后立即返回，但异常必须被托管、不能裸 `create_task` 丢异常。📖 https://docs.langchain.com/oss/python/langgraph （ainvoke，需核实锚点）
- **死信（dead letter）**：解析失败的负载存盘不丢，返回 200 防重试风暴。*Java 类比*：MQ 消费失败进死信队列。

### 🟢 第 1 段 · 复刻
- **读什么**：M3/M4 已编译的图的 `graph.ainvoke(state, config)` 调用方式。
- **跑什么**：在 webhook 里同步调一次图跑通（先不异步）。

### 🟡 第 2 段 · 改造
- 改成受应用生命周期管理的后台执行器调 `ainvoke`；任务状态/异常持久化到 incident 表；进程退出时停收新任务并等待/取消在途任务。

### 🔴 第 3 段 · 自研
- **从空文件**写死信逻辑：解析异常时把原始 payload 写 `runs/deadletter/<ts>.json` 并返回 200。

**落笔顺序**
1. 复刻：webhook 里同步 ainvoke 跑通。
2. 改造成异步后台执行 + 异常持久化。
3. 发一次坏 payload：看是否落死信 + 返回 200。
4. 自研死信目录 + 恢复排查说明。
5. 验证坏负载不触发 AM 重试。

**关键提示（≤3）**
- 别裸 `asyncio.create_task` 后丢异常——异常要落 incident 表可观察。
- 坏负载返回 200 是故意的（防 AM 重试风暴），但必须落死信可追。
- 进程退出要优雅：停收新任务、处理在途。

**卡住降级路径（30 分钟没思路）**
- 异步任务悄悄失败：给后台任务包 try/except 把异常写库并打日志。
- 视频：video-2 P66「异步+并发执行工具类」讲异步执行思路（可参考）。
- 问 AI：「FastAPI 里我想收到 webhook 后异步启动一个 LangGraph 图并立即返回 200，但异步任务的异常要能查到，正确写法是什么？给结构不要整段实现。」

**真实 HTTP 验收（含异常路径）**
```bash
curl -XPOST localhost:8000/webhook/alertmanager -d @am-fixture.json   # 200，后台图启动，incidents.db 状态推进
curl -XPOST localhost:8000/webhook/alertmanager -d '{"bad":"payload"}' # 200，runs/deadletter/ 落一份
ls runs/deadletter/    # 有坏负载文件
```
判定特征：正常负载异步启图；坏负载落死信 + 200；后台异常可在 incident 表/日志查到。依赖真实服务：网关、LLM（图内会真实调用）。

**完成判定**
- [ ] webhook 异步启图、立即返回 200
- [ ] 后台任务异常持久化到 incident 表、可观察
- [ ] 坏 payload 落死信文件并返回 200
- [ ] 进程退出优雅处理在途任务

---
*版本 C 特征：入口全靠"复刻真实 AM 负载/ainvoke 调用 → 改造成去重与异步启图 → 自研死信兜底"。*
