# 模块 9 · verify 跨家族验收节点 — 版本 C

> 编排理念：🟡在本项目真实代码上改造（M4 写的确定性预检**原样保留**，在它后面接一段跨家族 LLM 核验）→ 🔴自研引用存在性校验与验收缺席标记。**反幻觉双保险：代码先核引用真实存在，异厂商模型再核证据是否支撑结论。**

**本模块的能力边界（先对齐）**
- **`verify` 节点不是新建的**：M4 为了让条件边成立，已经在它里面写了**确定性预检的第一版**（`metrics_result` 为空 / `degraded=true` → fail），M6 把它搬进了 `nodes/verify.py`。本模块是往**同一个文件**里加第二段，**不重写第一段**。
- **M4 的回流边已经在了**：`verify → fail 且 rounds<2 → investigate` 这条边 M4 就接好并实测过。本模块只是让 fail 的判定从"证据取没取到"升级成"证据支不支撑结论"，回流机制一行不改。
- **入口是 M5 的 FastAPI，端口 8000**；证据台账来自 M7 的 `EvidenceStore`；被核验的报告来自 M7 的 `report_draft.md`。
- **本模块定义 `VerdictEnum` / `Verdict`**，并把 M4 占位的 `IncidentState.verify_verdict: dict | None` 收紧成 `Verdict | None`。
- **工具集不变**：verify 节点**故意不给任何工具**——它只看已有证据，不允许自己去补查（补查是 investigate 的活，verify 去查就失去了独立性）。

## 学完本模块你能做到的 3 件事
1. 用确定性代码先核验报告引用的 `EV-*` 都真实存在，不存在直接 fail、**一分钱 LLM 都不花**。
2. 用与排查**异厂商家族**的模型（OpenRouter）独立核验"证据是否支撑结论、有无未排除的竞争假设"。
3. 让 verify 的结论驱动 M4 已有的回流（最多 2 轮），并支持消融 A3（关掉 verifier）。

## 本模块交付物清单（文件路径级）
- `iris-agent-platform/src/agent/nodes/verify.py`（**改造**：M4 的确定性预检 + 本模块新增的引用存在性校验 + 跨家族 LLM 段）
- `iris-agent-platform/src/agent/state.py`（**改造**：新增 `VerdictEnum` / `Verdict`，`IncidentState.verify_verdict` 类型收紧）
- `iris-agent-platform/src/agent/config.py`（**改造**：新增 `verifier_enabled` 消融开关 A3）
- `runs/incidents/{id}/verify/verdict.md`（运行时产物）

---

## 任务 T9.1 · 引用存在性校验（确定性第二关）

**id**：T9.1 ｜ **所属模块**：M9 ｜ **优先级**：P0

**一句话目标**：在 M4 已有的"证据取到没有"之后，再加一道纯代码的"报告引用的证据编号是否真实存在"，不合格直接 fail 不调 LLM。

**前置知识（≤1 新概念）**
- **两级确定性预检（廉价关卡前置）**
  1. **M4 已写的第一关**：这一轮到底取到证据没有（`metrics_result` 空 / `degraded`）。
  2. **本任务的第二关**：报告里引用的每个 `EV-*` 编号，在 `evidence/` 里是否真有对应文件。这是最典型的幻觉形态——模型编一个看起来很合理的编号。
  3. 两关都是纯代码、零成本，必须排在昂贵的 LLM 核验**之前**。
  4. *Java 类比*：接口入参先做廉价的存在性校验，不合规立即拒，不进昂贵的下游调用。

### 🟡 第 1 段 · 改造（在 M4 的预检后面串一道）

`nodes/verify.py` 的结构改成三段串联，**任一段 fail 就短路返回**：

```python
def verify_node(state, model=None) -> dict:
    # 第 1 关（M4 已有，原样保留）：这一轮取到证据了吗
    # 第 2 关（T9.1 新增）：报告引用的 EV-* 都存在吗
    # 第 3 关（T9.2 新增）：跨家族 LLM 核验证据是否支撑结论
```

第 2 关的实现要点：
1. 读 `draft_report_ref` 指向的草稿全文；
2. 用 `re.findall(r"EV-[MLTS]-\d{3}", draft_text)` 抽出全部引用编号；
3. 与 `EvidenceStore(incident_id).list_ids()` 求差集；
4. **两种 fail 情形都要判**：
   - 引用了不存在的编号 → `Verdict(fail, ["引用的证据 EV-M-099 不存在"])`；
   - **一个引用都没有** → `Verdict(fail, ["结论未引用任何证据"])`（M7 的 prompt 要求条条带引用，一条都没有说明它没照做）。

先在 `state.py` 里补上本模块负责的模型：

```python
class VerdictEnum(StrEnum):
    """验收结论。只有三种，别再加中间态——回流判定靠它做分支."""

    PASS = "pass"    # 证据充分且支撑结论
    FAIL = "fail"    # 引用不存在 / 证据不支撑结论 / 有未排除的竞争假设
    ABSENT = "absent"  # 验收缺席：A3 关闭了 verifier，或 LLM 段解析失败


class Verdict(BaseModel):
    """一次验收的结论，由 M9 的 verify 节点写入 IncidentState.verify_verdict."""

    verdict: VerdictEnum
    objections: list[str] = []  # 异议清单，每条一句话；fail 时必须非空。
                                # 它同时是回流的输入——M4 的回流会把它追加进 verifier_feedback
                                # 注入下一轮 investigate 的 prompt，所以措辞要是"可执行的补证要求"
                                # （"缺少 pm-payment 的线程池指标佐证"）而不是"证据不足"这种空话。
    checked_by: str | None = None  # 实际执行核验的模型名，用于事后确认真的走了异家族；预检 fail 时为 None
```

**落笔顺序**
1. 在 `state.py` 写 `VerdictEnum` / `Verdict`，把 M4 的确定性预检返回值从 dict 换成 `Verdict`。
2. 把 `IncidentState.verify_verdict` 的类型从 `dict | None` 收紧为 `Verdict | None`。
3. 加第 2 关：抽正则 → 查存在性 → 差集非空则 fail。
4. 跑一次真实事故确认正常路径仍然 pass（别一上来就把好报告也判 fail 了）。
5. 手工把 `report_draft.md` 里的一个引用改成 `EV-M-099`，重跑 verify，确认 fail 且**日志里没有 LLM 调用**。
6. 再测"一个引用都没有"的分支：把草稿里的引用全删掉。

**关键提示（≤3）**
- 第 2 关要短路：判出 fail 就直接 return，别让流程继续走到 LLM 段——省钱是次要的，重要的是"引用都编造了，语义核验没有意义"。
- `objections` 的措辞是**下一轮 investigate 的输入**，写成可执行的补证要求，不是评语。
- 正则只匹配 `EV-[MLTS]-\d{3}`，和 M7 `EvidenceStore.new_id` 的产出格式必须逐字一致，两边各 grep 一次。

**卡住降级路径（30 分钟没思路）**
- 正常报告也被判 fail：把抽出来的编号集合和 `list_ids()` 的结果都打出来对，多半是大小写或位数（`EV-M-1` vs `EV-M-001`）对不上。
- 差集永远为空：确认 `list_ids()` 扫的是本 incident 的目录，不是空目录。
- 问 AI：「我要从一段 Markdown 里抽出所有形如 EV-M-001 的证据编号，并和目录里实际存在的文件名比对，正则和比对逻辑怎么写最稳？」

**真实 HTTP 验收（含异常路径）**
```bash
cd iris-agent-platform
uv run uvicorn agent.app:app --port 8000

# 正常路径：跑一次完整事故，第 2 关应放行
curl -s -XPOST localhost:8000/webhook/alertmanager -d @some-fault-alert.json
curl -s localhost:8000/incidents/inc-YYYYMMDD-001 | jq '.verdict, .objections'

# 异常路径 ①：把 report_draft.md 里一个引用改成 EV-M-099（不存在）后重跑 verify
#   期望：verdict=fail、objections 指名 EV-M-099、日志里没有 verify 模型的调用记录
# 异常路径 ②：把草稿里所有 [EV-*] 引用删光
#   期望：verdict=fail、objections="结论未引用任何证据"
```
判定特征：引用不存在 / 无引用两种情形都被确定性拦住且不调 LLM；正常报告不被误伤。依赖真实服务：M5 入口、M7 的证据产出（本任务本身不依赖任何模型）。

**完成判定**
- [ ] `VerdictEnum`（3 值）/ `Verdict`（3 字段）定义在 `state.py`
- [ ] `IncidentState.verify_verdict` 已从 `dict | None` 收紧为 `Verdict | None`
- [ ] M4 的确定性预检**原样保留**，本任务是在它后面串一关，不是重写
- [ ] 引用不存在 → fail 且不调 LLM（日志/账单可证）
- [ ] 一个引用都没有 → 也 fail
- [ ] 正则格式与 M7 `new_id` 的产出逐字一致

---

## 任务 T9.2 · 跨家族 LLM 核验 + 验收缺席 + A3 消融

**id**：T9.2 ｜ **所属模块**：M9 ｜ **优先级**：P0

**一句话目标**：用与 investigate 异厂商的模型独立核验"证据是否支撑结论"，并把"核验没做成"这件事诚实地标出来。

**前置知识（≤2 新概念）**
- **跨家族核验（反幻觉，H8）**
  1. 排查用 DashScope(Qwen)，验收换 OpenRouter 代理的**另一个家族**（如 Claude/GPT）——同一家族的模型往往共享同样的盲区，自己查自己等于没查。
  2. `get_model(AgentRole.VERIFY)` 已经在 M3 就按角色分好了，本模块只需确认它**真的**路由到了另一家（把实际 model 名打出来，别假设配置是对的）。
  3. 📖 https://docs.langchain.com/oss/python/langchain/structured-output （结构化输出，需核实锚点）· video-2 P61-64「评估器案例」
- **核验者的输入必须是"材料"而不是"推理过程"**
  1. 输入**只有**两样：证据文件全文（`EvidenceStore.read` 逐份读出）+ 草稿报告。
  2. **不给** investigate Agent 的中间推理、工具调用轨迹、假设列表——给了就会被它的思路带跑，独立性归零。
  3. 也**不给** verify 任何工具：它不能自己去补查，只能基于现有材料判断。
  4. *Java 类比*：复核人只看提交的凭证和结论，不看经办人的草稿本。

### 🟡 第 1 段 · 改造（在真实 verify 节点上接第 3 关）

1. 先用一次**真实跑出来的**证据 + 草稿，直接在 `verify_node` 里调一次 `get_model(AgentRole.VERIFY).with_structured_output(Verdict)`，把返回的对象和实际 model 名打出来——这一步同时验证了两件事：异家族模型的结构化输出能成、路由确实到了另一家。
2. prompt 组织（分点写进去）：
   1. **材料**：逐份证据全文（带 frontmatter，让核验者看得到 `degraded`/`truncated` 标记）+ 草稿报告全文；
   2. **核验点 A**：报告的每条断言，引用的证据是否**真的**支撑它（注意证据 frontmatter 上标了 `degraded`/`truncated` 的，支撑力要打折）；
   3. **核验点 B**：有没有**未排除的竞争假设**——prompt 里要明示"同一症状常见的鉴别组"（如 p99 升高可能是 db 慢查询、也可能是下游超时、也可能是 GC），逼它逐个说明为什么被排除或没被排除；
   4. **输出**：`Verdict{verdict, objections}`，fail 时 `objections` 必须给出**具体缺什么证据**。
3. `checked_by` 填实际模型名，落进 state 和 `verdict.md`。
4. 落盘 `runs/incidents/{id}/verify/verdict.md`（目录走 M5 的 `incident_dir()`），格式：

```markdown
---
verdict: fail
checked_by: <实际 model 名>
round: 1
ts: 2026-08-05T10:31:07Z
---

## 异议
1. EV-M-002 只显示 pm-question 的 p99 升高，未排除下游 pm-payment 超时导致的传导；建议补 query_trace 证据。
2. ...
```

### 🔴 第 2 段 · 自研（验收缺席 + A3 消融）

**从空文件**写这两条"诚实路径"：
1. **LLM 段解析失败 / 调用异常** → 不许当成 pass 蒙混过去，也不许当 fail 把流程卡死：返回 `Verdict(verdict=ABSENT, objections=["跨家族核验未完成：<原因>"])`，M8 的报告会据此打"验收缺席，未经跨家族核验"的标注；
2. **A3 消融开关**：`settings.verifier_enabled=False` 时**跳过第 3 关**（第 1/2 关的确定性预检仍然跑——那两关是免费的，没有理由关），返回 `Verdict(verdict=ABSENT, objections=["verifier_enabled=False（消融 A3）"])`；
3. 路由影响：`ABSENT` 在 M4 的 `route_after_verify` 里按 **pass** 处理（不触发回流），但报告必须标注——这是"缺席"和"通过"的区别所在。M4 的路由函数要相应补一个分支。

**落笔顺序**
1. 在 verify 节点里直接调一次真实的 `get_model(AgentRole.VERIFY)`，打印 model 名确认是异家族。
2. 接 `with_structured_output(Verdict)`，用真实证据 + 草稿跑一次，看它给什么结论。
3. 把 prompt 的 4 个要点补全（尤其"竞争假设鉴别组"那条），再跑一次对比结论质量。
4. 落盘 `verdict.md`。
5. 自研缺席分支：把 verify 的模型 key 改错，确认返回 `ABSENT` 而不是崩、也不是假 pass。
6. 自研 A3：`verifier_enabled=False` 跑一次，确认跳过第 3 关但前两关仍执行。
7. 补 M4 路由函数的 `ABSENT` 分支，确认它不触发回流。
8. 端到端验证回流：让 verify 真判一次 fail，确认 M4 的回流把 `objections` 注入了下一轮 investigate 的 prompt。

**关键提示（≤3）**
- verify 的输入不能含主 Agent 的推理过程，也不能给它工具——两者任一破了独立性就没了。
- `ABSENT` ≠ `PASS`：路由上按通过处理（否则一个模型故障就把所有事故卡死），但报告里必须标注，绝不能静默。
- 确认异家族靠**打印实际 model 名**，别靠"配置里应该是对的"。

**卡住降级路径（30 分钟没思路）**
- 跨家族没生效：打印 `get_model(AgentRole.VERIFY)` 拿到的实例和 model 名，确认路由到了 OpenRouter 而不是回落到 DashScope。
- verify 恒 pass：多半是 prompt 没给鉴别组，模型看不出"还有别的可能"；把竞争假设那一条写具体。
- verify 恒 fail：检查是不是把 `degraded`/`truncated` 的证据一律当成无效——它们支撑力打折但不是零。
- 视频：video-2 P61-64「评估器案例」是最贴近"用模型核验结果"的讲解。
- 问 AI：「我要让 verify 节点只拿证据文件和草稿报告去核验，不给它主 Agent 的推理，怎么组织输入和 prompt 才能保证独立核验并逼它检查竞争假设？」

**真实 HTTP 验收（含异常路径）**
```bash
cd iris-agent-platform
uv run uvicorn agent.app:app --port 8000

# 正常：跑一次完整事故，拿到跨家族 verdict
curl -s -XPOST localhost:8000/webhook/alertmanager -d @some-fault-alert.json
cat runs/incidents/inc-*/verify/verdict.md      # checked_by 是 OpenRouter 侧的模型名
curl -s localhost:8000/incidents/inc-YYYYMMDD-001 | jq '.verdict, .objections'

# 异常路径 ①：把 verify 角色的模型 key 改错
#   期望：verdict=absent，报告里出现"验收缺席，未经跨家族核验"，流程不卡死
# 异常路径 ②：verifier_enabled=False（A3）
#   期望：跳过 LLM 段但第 1/2 关仍执行；引用造假仍然能被拦住
# 异常路径 ③：让 verify 真判一次 fail
#   期望：M4 的回流被触发，下一轮 investigate 的 prompt 里出现 objections 原文，
#         且第二轮是增量补证（M7 的行为），rounds 到 2 后转 report
```
判定特征：正常跑出跨家族 verdict 且 `checked_by` 确属另一家族；模型故障走 absent 并被报告标注；A3 关闭 LLM 段但保留确定性关卡；fail 能真实驱动 M4 的回流。依赖真实服务：OpenRouter（verify）、DashScope（investigate 侧）、M2 网关。

**完成判定**
- [ ] LLM 段走 OpenRouter 另一家族，`checked_by` 记录了实际 model 名（打印为证）
- [ ] verify 的输入只有证据全文 + 草稿，**不含**主 Agent 推理，且 verify 没有任何工具
- [ ] prompt 明示竞争假设鉴别组，`objections` 是可执行的补证要求
- [ ] `verdict.md` 落盘，走 M5 的 `incident_dir()`
- [ ] LLM 段失败 → `ABSENT` 而非假 pass，报告有"验收缺席"标注
- [ ] `verifier_enabled=False`（A3）跳过 LLM 段但保留第 1/2 关
- [ ] M4 的 `route_after_verify` 补了 `ABSENT` 分支且不触发回流
- [ ] fail 能真实触发 M4 回流，`objections` 出现在下一轮 investigate 的 prompt 里

---
*版本 C 特征：验收全靠"在 M4 已有的确定性预检上串关 → 改造出跨家族语义核验 → 自研缺席标记与消融开关"，把"引用真实 + 语义可信"两道关分给代码和异家族模型各守一道，且"没核成"这件事永远诚实地写在报告上。*
