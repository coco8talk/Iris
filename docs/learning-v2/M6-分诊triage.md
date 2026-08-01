# 模块 6 · 分诊节点 triage — 版本 C

> 编排理念：🟢复刻结构化输出官方样例 → 🟡改造成 TriageResult 分诊 → 🔴自研失败降级 + 快慢路径。

## 学完本模块你能做到的 3 件事
1. 让轻模型（DashScope）把一组告警判成结构化的 `TriageResult`（严重度 + 疑似域 + 快慢路径）。
2. 模型输出解析失败时自动降级为"全面排查"，绝不让流程卡死。
3. 命中历史相似案例时走"快速路径"，为后面 M11 的知识闭环埋点。

## 本模块交付物清单（文件路径级）
- `src/.../graph/nodes/triage.py`
- （复用）`src/.../graph/state.py` 的 `TriageResult`

---

## 任务 T6.1 · triage 结构化输出 + 降级 + 快慢路径

**id**：T6.1 ｜ **所属模块**：M6 ｜ **优先级**：P0

**一句话目标**：把"一堆告警"变成一个可靠的结构化分诊结果，且永不因解析失败而卡死。

**前置知识（≤2 新概念）**
- **结构化输出 with_structured_output(Pydantic)**：让 LLM 直接产出符合 Pydantic 模型的对象，而非自由文本再解析。*Java 类比*：像让接口把响应反序列化进一个 DTO（`@RequestBody` 的反向），schema 就是契约。📖 https://docs.langchain.com/oss/python/langchain/structured-output （需核实锚点）· video-2 P85-88「输出解析和结构化」
- **快慢路径 + 疑似域预筛**：命中历史相似案例（相似度≥阈值）走快速验证；疑似域（db/threadpool/jvm/...）用于 M11 的 Skills 预筛。*Java 类比*：像缓存命中走快路径、未命中走全量查询；疑似域像给下游打的路由标签。📖（自研；阈值 `fast_path_similarity` 配置）

### 🟢 第 1 段 · 复刻
- **读什么**：LangChain `with_structured_output` 官方最小样例（喂一段文本→产出一个 Pydantic 对象）。
- **跑什么**：照样例用 DashScope 轻模型跑一次，拿到一个结构化对象——先证明结构化输出在你这条模型上能成。

### 🟡 第 2 段 · 改造
- 写 `triage.py`：prompt = 告警列表 + matched_cases 摘要 + 疑似域候选（固定枚举 `db|threadpool|jvm|resource|container|config|cache`）；`get_model("triage").with_structured_output(TriageResult)`。

### 🔴 第 3 段 · 自研
- **从空文件**写降级与快路径：解析失败重试 1 次，再失败返回默认 `TriageResult(severity="P2", suspected_domains=<全部>, fast_path=False)`；`matched_cases[0].similarity >= settings.fast_path_similarity` 时 `fast_path=True, candidate_root=...`。

**落笔顺序**
1. 复刻官方结构化输出样例跑通。
2. 改造成 triage prompt + TriageResult。
3. 发一次请求：喂真实告警 fixture，看 suspected_domains 对不对。
4. 自研降级分支：喂一个会让模型乱答的输入，验证走默认值不崩。
5. 自研 fast_path：构造 similarity 0.849/0.851 两个边界。

**关键提示（≤3）**
- 模型经**依赖注入**传入节点（不在节点内 get_model），这样 M12 消融和测试都好切。
- 疑似域枚举必须和 M11 的 Skills 预筛映射表**逐字一致**，否则预筛失灵。
- 降级默认值要"全面排查"（疑似域全开），保证不漏。

**卡住降级路径（30 分钟没思路）**
- 结构化输出老失败：确认该模型支持 tool/JSON 模式；换 prompt 让字段更明确。
- 视频：video-2 P86-87「输出解析和结构化」逐步讲解；video-1 P42-44「Pydantic 高级特性」兜底。
- 问 AI：「DashScope qwen 用 with_structured_output(TriageResult)，severity 字段老是返回不在枚举里的值，我的 Pydantic 定义和 prompt 这样（贴出），怎么约束它？」

**真实 HTTP 验收（含异常路径）**
```bash
# 用某故障的真实告警 fixture 触发，只跑到 triage 观察
curl -XPOST localhost:8000/webhook/alertmanager -d @slow-query-alert.json
# 查 state 里的 triage：suspected_domains 应含 "db"
sqlite3 runs/checkpoints.db "... triage ..."
# 异常路径：喂一段畸形/无关告警 → triage 走默认值（severity=P2，疑似域全开），流程继续
```
判定特征：真实告警分诊出正确疑似域；畸形输入走默认值不卡死；fast_path 阈值边界正确。依赖真实服务：DashScope。

**完成判定**
- [ ] 官方结构化输出样例复现（复刻段）
- [ ] 真实告警分诊出正确 suspected_domains
- [ ] 两次解析失败 → 走默认值，流程不卡死
- [ ] fast_path 阈值边界（0.849 不触发 / 0.851 触发）正确
- [ ] 疑似域枚举与 M11 Skills 预筛映射一致

---
*版本 C 特征：分诊全靠"复刻结构化输出样例 → 改造成 TriageResult → 自研降级与快慢路径"，第一次把 LLM 的输出变成可靠契约。*
