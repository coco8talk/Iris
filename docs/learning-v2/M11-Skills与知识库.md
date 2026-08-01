# 模块 11 · Skills(SOP) + Chroma 知识库 + 快速路径 — 版本 C

> greenfield。🟢复刻官方格式/样例 → 🟡改造成本项目 SOP 与案例库 → 🔴自研 loader 与快速路径。

## 学完本模块你能做到的 3 件事
1. 用 anthropics/skills 的 SKILL.md 格式写领域 SOP，并按 triage 疑似域**渐进披露**（只给相关的）。
2. 用 Chroma 把历史案例向量化、按症状检索相似案例。
3. 让相似案例命中时走"快速路径"，二次同类故障的排查步数可观测地下降。

## 本模块交付物清单（文件路径级）
- `src/.../skills/loader.py` + `src/.../skills/*/SKILL.md`（7 个 SOP）
- `src/.../knowledge/store.py`（Chroma）+ `src/.../knowledge/cases.py`
- `src/.../graph/nodes/{knowledge_match,knowledge_update}.py`

---

## 任务 T11.1 · Skills 机制（渐进披露）+ loader

**id**：T11.1 ｜ **所属模块**：M11 ｜ **优先级**：P0

**一句话目标**：从 0 写 SKILL.md 加载/预筛/按需读取机制 + 第一个通用 Skill。

**前置知识（≤2 新概念）**
- **Skills 渐进披露（SKILL.md 三段式）**：先给 lead 一份"全部 Skill 的 name+description 一览"，命中疑似域才注入具体 Skill 全文。*Java 类比*：像按需加载的 SOP/策略文档——先看目录，用到哪篇才读正文，省上下文。📖 https://github.com/anthropics/skills （SKILL.md 格式，需核实锚点）· video-3 P1-6「什么是 Skills·两种架构代码实战」
- **疑似域预筛映射**：triage 的疑似域（db/threadpool/jvm/...）→ 对应 Skill 列表；config-change-first 标"通用，任何诊断必列"。*Java 类比*：按标签路由到相关规则集。📖（自研）

### 🟢 第 1 段 · 复刻
- **读什么**：anthropics/skills 仓库里一个真实 SKILL.md 的 frontmatter（name/description）+ 正文结构。
- **跑什么**：照格式手写一份 `config-change-first/SKILL.md`（判别流程/根因→证据要求/修复建议），先跑通"能被解析"。

### 🟡 第 2 段 · 改造
- 写 `loader.py`：扫 `skills/*/SKILL.md` 解析 frontmatter；`listing_for(domains)` 按疑似域预筛（映射表 db→[mysql-slow-query] 等，config-change-first 恒列）；`read_skill(name)` 返回全文；内容里工具名全用 M2 的模板名。

### 🔴 第 3 段 · 自研
- **从空文件**写 frontmatter 解析（`---` 分割，yaml 手撕两行，不引 python-frontmatter）；`listing()` 全量一览；`versions()`（供 M12 评估记 metadata）；`skills_enabled=False` 时 lead 不注入（A1 消融）。

**落笔顺序**
1. 复刻 anthropics/skills 格式，手写第一个 SKILL.md。
2. 改造出 loader + 预筛映射。
3. 发一次调用：给一组疑似域，看 listing_for 返回对不对。
4. 自研 frontmatter 解析 + A1 开关。
5. 验证 read_skill 全文可读。

**关键提示（≤3）**
- 疑似域枚举与 M6 triage 的**逐字一致**，否则预筛失灵。
- config-change-first 恒在列（任何诊断先问"最近改了什么"）。
- 每个 Skill 引用的工具名必须在 M2 真实存在（写完 grep 校验一遍）。

**卡住降级路径（30 分钟没思路）**
- 预筛不对：打印 listing_for 输入的 domains 和映射表，逐条对。
- 视频：video-4 P7「Skills 自我进化」+ P20-21 建立 Skills 直觉（本项目不做自进化，仅渐进披露）。
- 问 AI：「我要写一个 SKILL.md loader，解析 frontmatter 的 name/description，按传入的疑似域返回相关 Skill 列表，两行 yaml 手撕怎么写最简？」

**真实 HTTP 验收（含异常路径）**
```bash
uv run python -c "from ...skills.loader import listing_for; print(listing_for(['db']))"   # 含 mysql-slow-query + config-change-first
uv run python -c "from ...skills.loader import read_skill; print(read_skill('config-change-first')[:200])"  # 全文
# 异常路径：skills_enabled=False → lead prompt 不含 skill 列表（A1）
```
判定特征：预筛映射正确且 config-change-first 恒在；read_skill 返回全文；A1 关闭时不注入。依赖真实外部服务：无（纯本地机制）。

**完成判定**
- [ ] anthropics/skills 格式的第一个 SKILL.md 可解析（复刻段）
- [ ] loader 预筛映射正确、config-change-first 恒在
- [ ] 疑似域枚举与 M6 一致
- [ ] skills_enabled=False 时不注入（A1）
- [ ] 每个 Skill 工具名都在 M2 存在（grep 通过）

---

## 任务 T11.2 · Chroma 知识库 + 快速路径

**id**：T11.2 ｜ **所属模块**：M11 ｜ **优先级**：P0

**一句话目标**：从 0 接 Chroma 做案例检索与沉淀，并让相似案例命中走快速路径。

**前置知识（≤2 新概念）**
- **向量检索（Chroma）**：把案例症状 embedding 后存库，新告警按症状检索最相似的历史案例。*Java 类比*：像一个"按语义相似度"的搜索引擎，检索键是症状不是关键词。📖 https://docs.langchain.com/oss/python/integrations/vectorstores/chroma （需核实锚点）· video-2 P104-111「Embeddings·语义搜索案例」
- **沉淀门槛（只入好案例）**：只有 verdict=pass 且获批的案例才入向量库，驳回的只落 Markdown 作负样本。*Java 类比*：只把复核通过的记录入正式知识库，废案单独归档。📖（自研）

### 🟢 第 1 段 · 复刻
- **读什么**：langchain-chroma 官方 `PersistentClient` + `add` + `similarity_search` 最小样例。
- **跑什么**：照样例 add 两条、search 一次，看相似度排序——先证明向量检索能用。

### 🟡 第 2 段 · 改造
- 写 `store.py`：collection `cases`，`add_case(case_id,text,metadata)` / `search(text,k=3)->KnowledgeHit[]`（embedding 走 `get_embeddings()`，similarity=1-distance，检索异常返回 `[]` 不阻塞）；`cases.py` 案例 Markdown 模板（向量化文本=症状段）。

### 🔴 第 3 段 · 自研
- **从空文件**写 `knowledge_match`（告警→search→matched_cases）、`knowledge_update`（门槛：pass 且 approved 才 add_case，驳回只落 Markdown）；快速路径：`triage.fast_path` True 时 lead 注入"候选根因，优先最小取证验证"。

**落笔顺序**
1. 复刻 chroma 官方 add/search 样例。
2. 改造出 store + 案例模板。
3. 发请求：knowledge_match 对新告警检索。
4. 自研 update 门槛 + 快速路径注入。
5. 验证二次同类故障步数下降。

**关键提示（≤3）**
- 向量化的是**症状**不是结论（检索键是"看到什么"，不是"结论是什么"）。
- 检索异常返回空、不阻塞主流程。
- 只有 pass+approved 入库，防错误案例污染知识库。

**卡住降级路径（30 分钟没思路）**
- 检索不准：确认 embedding 模型可用、向量化的是症状段。
- 视频：video-1 P112-116「嵌入模型·向量库·Milvus」兜底（本项目用 Chroma，概念通用）。
- 问 AI：「langchain-chroma 里我想按案例症状检索相似历史案例，metadata 存根因，add_case 和 search 怎么组织？相似度阈值怎么定 fast_path？」

**真实 HTTP 验收（含异常路径）**
```bash
# 首次注入某故障 → 完整闭环 → 获批入库
curl -XPOST localhost:8000/webhook/alertmanager -d @fault-A.json   # 首次，记步数
# 再次注入同类 → 走快速路径
curl -XPOST localhost:8000/webhook/alertmanager -d @fault-A.json   # 二次
# 网关审计对比步数
sqlite3 tool-gateway/data/*.db "select incident_id,count(*) from audit_log group by incident_id"  # 二次显著低于首次
# 异常路径：驳回的 incident → 不进向量库、只落 Markdown
```
判定特征：add→search 命中且相似度排序正确；二次同类步数下降；驳回案例不入向量库。依赖真实服务：embedding（DashScope）、M2 网关。

**完成判定**
- [ ] chroma add/search 样例复现（复刻段）
- [ ] 案例按症状向量化、检索命中且排序正确
- [ ] 只有 pass+approved 入库，驳回只落 Markdown
- [ ] 快速路径命中时二次步数下降（记两个数字）
- [ ] 检索异常返回空不抛

---
*版本 C 特征：知识闭环全靠"复刻官方 SKILL.md/chroma 样例 → 改造成本项目 SOP 与案例库 → 自研 loader/门槛/快速路径"。*
