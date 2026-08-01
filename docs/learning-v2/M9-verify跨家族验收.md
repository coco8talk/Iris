# 模块 9 · verify 跨家族验收节点 — 版本 C

> greenfield。🟢复刻 with_structured_output → 🟡改造成 Verdict 语义核验 → 🔴自研确定性预检 + 跨家族接线。**反幻觉双保险：代码先核引用真实存在，异厂商模型再核证据支撑结论。**

## 学完本模块你能做到的 3 件事
1. 用确定性代码先核验报告引用的 `EV-*` 都真实存在，不存在直接 fail、不花 LLM 钱。
2. 用与排查**异厂商家族**的模型（OpenRouter）独立核验"证据是否支撑结论、有无未排除的竞争假设"。
3. 让 verify 失败能触发 M4 的回流（最多 2 轮），并支持消融 A3（关掉 verifier）。

## 本模块交付物清单（文件路径级）
- `src/.../graph/nodes/verify.py`（两段式验收）
- `runs/incidents/{id}/verify/verdict.md`（运行时产物）

---

## 任务 T9.1 · verify 两段式（确定性预检 + 跨家族 LLM 核验）

**id**：T9.1 ｜ **所属模块**：M9 ｜ **优先级**：P0

**一句话目标**：从 0 写验收节点，先用代码卡住"引用不存在"的假报告，再用异家族模型核语义。

**前置知识（≤2 新概念）**
- **跨家族核验（反幻觉，H8）**：排查用 DashScope(Qwen)，验收换 OpenRouter 的**另一家族**（如 Claude/GPT），避免同源偏差。*Java 类比*：像关键决策双人复核，且复核人来自不同团队，防同一盲区。📖 https://docs.langchain.com/oss/python/langchain/structured-output （结构化输出，需核实锚点）· video-2 P61-64「评估器案例」
- **确定性预检**：正则抽报告里全部 `EV-[MLTS]-\d{3}`，任一在 `evidence/` 无对应文件 → 直接 fail，**不调 LLM**。*Java 类比*：接口入参先做廉价的存在性校验，不合规立即拒，不进昂贵的下游。📖（自研）

### 🟢 第 1 段 · 复刻
- **读什么**：`with_structured_output(Verdict)` 官方样例（复用 M6 学过的结构化输出）。
- **跑什么**：照样例让 OpenRouter 的某模型产出一个 `Verdict{verdict,objections}`，先证明异家族模型的结构化输出也能成。

### 🟡 第 2 段 · 改造
- 写 verify 的 LLM 段：`get_model("verify")`（OpenRouter，与 investigate 的 DashScope **异厂商**）；输入**只有**证据文件全文 + 草稿报告（不含主 Agent 推理，保独立性）；`with_structured_output(Verdict)`；核验点：引用证据是否支撑结论、有无未排除的竞争假设（prompt 明示同症鉴别组存在）。

### 🔴 第 3 段 · 自研
- **从空文件**写确定性预检段：正则抽全部 EV 引用 → 逐个查文件存在 → 缺失直接 `Verdict(fail,["引用 EV-x 不存在"])`；LLM 段解析失败 → 视为 pass 但打 `verifier_absent` 标记；消融 A3：`verifier_enabled=False` → 恒 pass。verdict 落盘。

**落笔顺序**
1. 复刻：OpenRouter 异家族模型产出 Verdict。
2. 改造：接真实证据 + 草稿，跨家族核验。
3. 发一次请求：篡改报告引用一个不存在的 EV → 应确定性 fail（不调 LLM）。
4. 自研预检 + A3 开关。
5. 验证 fail 能触发 M4 回流。

**关键提示（≤3）**
- 预检是纯代码，LLM 只做代码做不了的语义核验（职责边界）。
- verify 的输入不能含主 Agent 的推理过程，否则失去独立性。
- verify 用 OpenRouter 的**另一个家族**，别和 investigate 撞同一家。

**卡住降级路径（30 分钟没思路）**
- 跨家族没生效：确认 `get_model("verify")` 真的路由到 OpenRouter 而非 DashScope（打印 model 名）。
- 视频：video-2 P61-64「评估器案例」是最贴近"用模型核验结果"的讲解。
- 问 AI：「我要让 verify 节点只拿证据文件和草稿报告去核验，不给它主 Agent 的推理，怎么组织输入和 prompt 才能保证独立核验？」

**真实 HTTP 验收（含异常路径）**
```bash
# 正常：跑一次完整事故，verify 给出 verdict
curl -XPOST localhost:8000/webhook/alertmanager -d @some-fault-alert.json
cat runs/incidents/inc-*/verify/verdict.md
# 异常路径（确定性预检）：人为把 report 里引用改成 EV-M-099（不存在）→ verdict fail，且日志显示未调 LLM
```
判定特征：正常跑出跨家族 verdict；篡改引用 → 确定性 fail 不调 LLM；verify 模型确属 OpenRouter 家族。依赖真实服务：OpenRouter（verify）、（investigate 侧 DashScope）。

**完成判定**
- [ ] 异家族模型结构化输出 Verdict 复现（复刻段）
- [ ] 确定性预检：引用不存在直接 fail、不调 LLM
- [ ] LLM 段走 OpenRouter 另一家族、输入不含主 Agent 推理
- [ ] verify fail 触发 M4 回流；A3 开关能关掉 verifier
- [ ] verdict 落盘

---
*版本 C 特征：验收全靠"复刻结构化输出 → 改造成跨家族语义核验 → 自研确定性预检"，把"引用真实 + 语义可信"两道关分给代码和异家族模型各守一道。*
