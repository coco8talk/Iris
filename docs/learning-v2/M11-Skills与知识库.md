# 模块 11 · Skills(SOP) + Chroma 知识库 + 快速路径 — 版本 C

> 编排理念：🟡在本项目真实代码上改造（照 anthropics/skills 格式直接写本项目的 SOP；用 M8 真跑出来的报告当第一条入库案例）→ 🔴自研 loader、沉淀门槛与快速路径。

**本模块的能力边界（先对齐）**
- **入口是 M5 的 FastAPI，端口 8000**；lead（M10）已在跑，Skills 注入的对象就是它。
- **`TriageResult` 的 `fast_path` / `candidate_root` 两个字段在本模块补齐**：M6 只定义了 `severity` / `suspected_domains`，因为这两个字段依赖 `matched_cases`，而 `matched_cases` 要等本模块的 Chroma 检索才有真实来源。现在有了，才轮到写它们。
- **`Domain` 的 7 个字面值是 M6 定的**（`db`/`threadpool`/`jvm`/`resource`/`container`/`config`/`cache`），本模块的预筛映射表必须与它逐字对齐——写完两边各 grep 一次。
- **工具集不变**：M7 已把 7 个工具全部接入；Skills 与知识库都不新增工具，它们改变的是**注入 lead 的上下文**。
- **不造假案例**：第一条入库案例必须是 M8 真实跑出来的那份报告，不是手编的样例。

## 学完本模块你能做到的 3 件事
1. 用 anthropics/skills 的 SKILL.md 格式写领域 SOP，并按 triage 的疑似域**渐进披露**（只给相关的，不是全塞进去）。
2. 用 Chroma 把历史案例按**症状**向量化、检索相似案例，并只让"验收通过且获批"的案例进库。
3. 让相似案例命中时走"快速路径"，二次同类故障的排查步数可观测地下降（记两个真实数字）。

## 本模块交付物清单（文件路径级）
- `iris-agent-platform/src/agent/skills/loader.py`（**新建**）+ `src/agent/skills/*/SKILL.md`（**新建**：7 个 SOP，与 `Domain` 一一对应 + 1 个恒列的通用 SOP）
- `iris-agent-platform/src/agent/knowledge.py`（**新建**：Chroma 存取 + `KnowledgeHit`）
- `iris-agent-platform/src/agent/nodes/knowledge_match.py`、`knowledge_update.py`（**新建**）
- `iris-agent-platform/src/agent/state.py`（**改造**：`TriageResult` 补 `fast_path`/`candidate_root`；新增 `KnowledgeHit`）
- `iris-agent-platform/src/agent/nodes/triage.py`（**改造**：读 `matched_cases` 填快慢路径）
- `iris-agent-platform/src/agent/lead.py`（**改造**：注入 Skills 列表 + 快速路径提示）
- `iris-agent-platform/src/agent/config.py`（**改造**：`skills_enabled`（A1）、`fast_path_similarity`）
- `runs/knowledge/chroma/`、`runs/knowledge/cases/*.md`（运行时产物）

---

## 任务 T11.1 · Skills 机制（渐进披露）+ loader

**id**：T11.1 ｜ **所属模块**：M11 ｜ **优先级**：P0

**一句话目标**：写出本项目的 SOP 文件与加载/预筛/按需读取机制，让 lead 只拿到跟本次事故相关的那几篇。

**前置知识（≤2 新概念）**
- **Skills 渐进披露（SKILL.md 三段式）**
  1. 先给 lead 一份"全部 Skill 的 name + description 一览"（每篇一行，很便宜）。
  2. lead 判断需要哪篇，才调 `read_skill(name)` 拿全文（每篇 ≤600 token）。
  3. 好处是上下文只为**用到的**那几篇付费；坏处是多一轮工具调用——所以 description 必须写得让 lead 一眼判得出该不该读。
  4. *Java 类比*：按需加载的 SOP 文档——先看目录，用到哪篇才读正文。
  5. 📖 https://github.com/anthropics/skills （SKILL.md 格式，需核实锚点）· video-3 P1-6「什么是 Skills·两种架构代码实战」
- **疑似域预筛映射**
  1. M6 的 triage 判出的 `suspected_domains` → 对应的 Skill 列表，这一步在**代码里**做，不劳烦模型。
  2. `config-change-first` 标成"通用"，**任何诊断都恒列**——"最近改了什么"是 SRE 排查的第一问，不能等模型想起来。
  3. 预筛的输入是枚举值，映射表的 key 必须与 `Domain` 逐字一致，写错不报错、只是静默返回空列表。
  4. *Java 类比*：按标签路由到相关规则集。

### 🟡 第 1 段 · 改造（照官方格式写本项目的 SOP）

1. 读一份 anthropics/skills 仓库里真实 SKILL.md 的结构，然后**直接写本项目的第一篇** `skills/config-change-first/SKILL.md`——不要先写一个练手的样例文件。frontmatter 三个字段：

```markdown
---
name: config-change-first
description: 任何故障诊断的第一问——最近有没有配置发布、开关变更、版本上线。适用于所有疑似域，恒列。
version: 1
---

## 判别流程
1. 用 query_changes 查告警时间点前 1 小时内本服务及其直接下游的变更记录。
2. 有变更 → 用 query_metrics 对比变更前后的 error_rate/p99，确认时间相关性。
3. 无变更 → 明确排除配置类根因，在 hypotheses 里记一条"已排除"，不要留着悬空。

## 根因 → 证据要求
- 配置变更导致：需要 ①变更记录（时间 + dataId + 操作人）②变更前后指标对比 两类证据。

## 修复建议
- 确认为配置变更导致 → 建议 revert_config（M12 的受控动作，须经人工审批）。
```

2. 照同一格式写另外 7 篇，与 `Domain` 的 7 个值一一对应（`db` → `mysql-slow-query`、`threadpool` → `threadpool-exhaustion`、`jvm` → `jvm-gc-pressure`、`resource` → `host-resource-saturation`、`container` → `container-crashloop`、`config` → `config-change-first`（复用）、`cache` → `redis-cache-failure`）。
3. **每篇引用的工具名必须在 M7 已接入的 7 个里真实存在**（`query_metrics`/`query_logs`/`query_trace`/`query_cmdb`/`query_changes`/`query_metrics_raw`/`query_logs_raw`），写完 grep 校验一遍——SOP 里写一个不存在的工具名，等于教模型去撞墙。

### 🔴 第 2 段 · 自研（loader）

**从空文件**写 `skills/loader.py`，签名先定死：

```python
def listing() -> list[SkillMeta]:
    """扫 skills/*/SKILL.md，解析 frontmatter 返回全部 Skill 的 name/description/version 一览."""


def listing_for(domains: list[Domain]) -> list[SkillMeta]:
    """按疑似域预筛。规则：
    1. 逐个 domain 查 _DOMAIN_TO_SKILLS 映射表取并集；
    2. config-change-first 恒列（无论 domains 是什么）；
    3. domains 为空或全是未知值时，退化为 listing()（全量给，宁滥勿缺）。
    """


def read_skill(name: str) -> str:
    """返回某篇 SKILL.md 的正文全文（不含 frontmatter），≤600 token。
    name 会被拼进路径，先过白名单正则 ^[a-z][a-z0-9-]*$ 再拼，防路径穿越。"""


def versions() -> dict[str, int]:
    """{skill_name: version}，M12 的评估要把它记进 run metadata，
    这样"这次评估用的是哪版 SOP"事后查得到."""
```

frontmatter 解析**手撕两行**即可（按 `---` 切三段、逐行 `split(":", 1)`），不引 `python-frontmatter`——只有三个字段，多一个依赖不划算。

A1 消融：`settings.skills_enabled=False` 时 lead 不注入任何 Skill 列表（`lead.py` 里判断，不是在 loader 里返回空——loader 保持纯粹）。

**落笔顺序**
1. 写 `config-change-first/SKILL.md`，先只有这一篇。
2. 写 `listing()`，跑一次确认能解析出 frontmatter 三字段。
3. 补齐另外 7 篇 SKILL.md。
4. 写 `_DOMAIN_TO_SKILLS` 映射表 + `listing_for`，用 `[Domain.DB]` 试一次，确认返回 `mysql-slow-query` + `config-change-first`。
5. 写 `read_skill`，试 `read_skill("../../etc/passwd")` 确认被拒。
6. 接进 `lead.py`：注入 listing_for 的结果 + 把 `read_skill` 作为工具给 lead。
7. 跑一次真实事故，看 lead 有没有主动读了相关那篇。
8. 关掉 `skills_enabled` 再跑一次（A1 对照）。

**关键提示（≤3）**
- 映射表的 key 与 M6 `Domain` 的 7 个字面值**逐字一致**，两边各 grep 一次核对（`db` 不能一边写成 `database`）。
- `config-change-first` 恒在列——任何诊断先问"最近改了什么"。
- description 是给模型做判断用的，要写"什么时候该读我"，不是写"我是什么"。

**卡住降级路径（30 分钟没思路）**
- 预筛返回空：打印传进来的 `domains` 和映射表的 keys，对一遍字面值。
- lead 从来不读 Skill：description 太笼统，把触发条件写具体（"当疑似域含 db 或看到慢查询相关日志时读我"）。
- 视频：video-4 P7「Skills 自我进化」+ P20-21 建立 Skills 直觉（本项目不做自进化，只做渐进披露）。
- 问 AI：「我要写一个 SKILL.md loader，解析 frontmatter 的 name/description/version，按传入的疑似域返回相关 Skill 列表，两行 yaml 手撕怎么写最简？」

**真实验收（含异常路径）**
```bash
cd iris-agent-platform
uv run python -c "
from agent.state import Domain
from agent.skills.loader import listing, listing_for, read_skill, versions
print([s.name for s in listing_for([Domain.DB])])      # ['mysql-slow-query', 'config-change-first']
print([s.name for s in listing_for([])])               # 退化为全量
print(read_skill('config-change-first')[:200])
print(versions())
"
# 工具名校验：SOP 里出现的工具名必须都在真实定义里
grep -oE 'query_[a-z_]+' src/agent/skills/*/SKILL.md | sort -u
grep -oE '@tool\(\"[a-z_]+' src/agent/tools/definetions.py | sort -u   # 两边对照，前者是后者的子集

# 异常路径 ①：read_skill('../../etc/passwd') → 抛异常，不读到文件
# 异常路径 ②：skills_enabled=False 跑一次真实事故 → lead 的 prompt 里不含 skill 列表（A1）
```
判定特征：预筛映射正确且 `config-change-first` 恒在；SOP 里的工具名都真实存在；A1 关闭时不注入。依赖真实外部服务：无（纯本地机制，但注入效果要在真实事故里看）。

**完成判定**
- [ ] 8 篇 SKILL.md 写完，frontmatter 三字段齐全，与 `Domain` 7 值一一对应
- [ ] `listing_for` 预筛正确，`config-change-first` 恒在，空/未知 domains 退化为全量
- [ ] 映射表 key 与 M6 `Domain` 字面值逐字一致（两边 grep 核对通过）
- [ ] 每篇 SOP 引用的工具名都在 M7 已接入的 7 个里（grep 校验通过）
- [ ] `read_skill` 有 name 白名单，路径穿越被拒
- [ ] `versions()` 可用（M12 评估的依赖）
- [ ] `skills_enabled=False` 时 lead 不注入（A1）

---

## 任务 T11.2 · Chroma 知识库 + 沉淀门槛

**id**：T11.2 ｜ **所属模块**：M11 ｜ **优先级**：P0

**一句话目标**：接 Chroma 做案例检索与沉淀，用**真实跑出来的报告**当第一条案例，并卡住"只有好案例才进库"的门槛。

**前置知识（≤2 新概念）**
- **向量检索（Chroma）与"检索键是症状"**
  1. 把案例的**症状段**（看到了什么现象）embedding 后入库，新告警拿自己的症状去检索。
  2. **不要向量化结论**：新事故进来时你只有症状、没有结论，用结论当检索键等于用答案找答案。
  3. `similarity = 1 - distance`（Chroma 默认返回距离），阈值判断前先确认你用的距离度量是不是余弦。
  4. *Java 类比*：一个按语义相似度的搜索引擎，索引字段选错了整个检索就废了。
  5. 📖 https://docs.langchain.com/oss/python/integrations/vectorstores/chroma （需核实锚点）· video-2 P104-111「Embeddings·语义搜索案例」
- **沉淀门槛（只入好案例）**
  1. 只有 `verdict == pass` **且** `approval.approved == True` 的案例才 `add_case` 进向量库。
  2. 被驳回的只落 Markdown 归档，不进库——错误案例进了库会被后续事故检索到，污染是**累积**的。
  3. *Java 类比*：只把复核通过的记录入正式知识库，废案单独归档。

### 🟡 第 1 段 · 改造（用真实报告做第一条案例）

1. 先跑一次 M8 的完整流程，拿到一份**真实**的 `report.md` + 结构化字段——这就是第一条案例的原料，不要手编一条样例入库。
2. 写 `knowledge.py`：

```python
class KnowledgeHit(BaseModel):
    """一次检索命中的历史案例摘要，写进 IncidentState.matched_cases."""

    case_id: str        # 案例 id，等于当初那次事故的 incident_id
    similarity: float   # 1 - distance，越大越像；与 settings.fast_path_similarity 比较
    summary: str        # 案例的症状段摘要，给 triage/lead 看的
    root_service: str   # 当初的根因服务
    root_cause: str     # 当初的根因分类


def add_case(case_id: str, symptom_text: str, metadata: dict) -> None:
    """把一条案例写进 collection 'cases'。
    embedding 走 M3 的 get_embeddings()；向量化的是 symptom_text（症状段），
    metadata 里放 root_service/root_cause/report_path 等结论侧信息（只做过滤和回显，不参与检索）。"""


def search(symptom_text: str, k: int = 3) -> list[KnowledgeHit]:
    """按症状检索最相似的 k 条案例，按 similarity 降序。
    检索异常（Chroma 挂了/embedding 服务不可用）一律返回 [] 并打日志，
    绝不向上抛——知识库是加速器不是必需品，它挂了诊断照样得跑。"""
```

3. 案例 Markdown 模板（落 `runs/knowledge/cases/{case_id}.md`），五段：
   1. **症状**（← 这一段是向量化的文本）：告警内容 + 关键指标现象；
   2. **根因**：`root_service` + `root_cause` + `root_detail`；
   3. **证据**：`evidence_ids` 及各自摘要；
   4. **修复**：实际执行的动作与结果；
   5. **人工备注**：审批人留言（可为空）。
4. `nodes/knowledge_match.py`：从 `alerts_ref` 读原始告警 → 组装症状文本 → `search(k=3)` → 写 `state["matched_cases"]`；接在图里 **triage 之前**（triage 要用它判快慢路径）。

### 🔴 第 2 段 · 自研（沉淀门槛 + 快慢路径）

**从空文件**写 `nodes/knowledge_update.py`：
1. 门槛判断：`verdict == PASS and approval and approval.approved` 才 `add_case`；
2. 不满足门槛 → **只**写 `runs/knowledge/cases/{id}.md`（正文里标注"未入库：<原因>"），不碰向量库；
3. 接在图的末尾（M12 的 approval 之后）。

**在 `state.py` 里给 `TriageResult` 补上 M6 留白的两个字段**：

```python
class TriageResult(BaseModel):
    severity: Severity
    suspected_domains: list[Domain]
    # 以下两个字段由 M11 补齐——它们依赖 matched_cases，M6 时还没有真实来源
    fast_path: bool = False          # 命中高相似度历史案例时为 True，走"优先最小取证验证候选根因"
    candidate_root: str | None = None  # 命中案例的 root_service/root_cause，作为待验证的候选根因
```

**在 `nodes/triage.py` 里填快慢路径**（纯代码判断，不劳烦模型）：
- `matched_cases and matched_cases[0].similarity >= settings.fast_path_similarity` → `fast_path=True`，`candidate_root` 取该案例的 `root_service` + `root_cause`。

**在 `lead.py` 里消费**：`fast_path=True` 时 instructions 追加一段——"历史上有高度相似的案例，候选根因是 X；**优先用最小取证验证这个候选**，验证不成立再走完整流程"。注意措辞是"优先验证"不是"直接采信"，否则第一次判错的案例会被永久复制。

**落笔顺序**
1. 跑一次 M8 完整流程，拿到一份真实报告。
2. 写 `knowledge.py` 的 `add_case`，把这份真实报告作为第一条案例入库。
3. 写 `search`，用同一份告警的症状检索一次，确认能命中自己（similarity 应该很高）。
4. 再入一条**不同故障**的案例，检索时确认排序正确（同类在前）。
5. 写 `knowledge_match` 节点，接在 triage 之前。
6. 给 `TriageResult` 补两个字段，在 triage 里填快慢路径，测阈值边界（0.849 不触发 / 0.851 触发）。
7. 在 lead 里消费 `fast_path`，注入候选根因提示。
8. 写 `knowledge_update` + 门槛，测驳回路径。
9. 端到端：同一故障注入两次，对比网关审计步数。

**关键提示（≤3）**
- 向量化的是**症状**不是结论——检索键是"看到什么"，不是"答案是什么"。
- 检索异常返回空、不阻塞主流程；知识库挂了顶多退化成第一次那样慢，不能让事故诊断跟着挂。
- 快速路径的措辞是"优先验证候选根因"，不是"直接采信"——否则一次误判会被知识库永久放大。

**卡住降级路径（30 分钟没思路）**
- 检索命不中自己：确认入库和检索用的是同一个 embedding 模型，且向量化的都是症状段。
- similarity 数值看着不对：确认 Chroma 返回的是距离还是相似度、用的是哪种距离度量，`1-distance` 的前提是余弦距离。
- 步数没下降：看 lead 有没有真的读到 `fast_path` 提示；再看它是不是照样把三个 subagent 都派了一遍（提示里要写明"先只派验证候选所需的那一个"）。
- 视频：video-1 P112-116「嵌入模型·向量库」兜底（本项目用 Chroma，概念通用）。
- 问 AI：「langchain-chroma 里我想按案例症状检索相似历史案例、metadata 存根因，`add_case` 和 `search` 怎么组织？相似度阈值怎么定 fast_path？」

**真实 HTTP 验收（含异常路径）**
```bash
cd iris-agent-platform
uv run uvicorn agent.app:app --port 8000

# ① 首次注入某故障 → 完整闭环 → 审批通过 → 入库
curl -s -XPOST localhost:8000/webhook/alertmanager -d @fault-A.json
sqlite3 tool-gateway/data/*.db "select count(*) from audit_log where incident_id='inc-A';"  # 记下首次步数
ls runs/knowledge/cases/                    # 案例 Markdown 落盘

# ② 再次注入同类故障 → 应命中快速路径
curl -s -XPOST localhost:8000/webhook/alertmanager -d @fault-A.json    # 注意换 fingerprint 避开 M5 去重
sqlite3 tool-gateway/data/*.db "select incident_id, count(*) from audit_log group by 1;"
# 期望：二次的步数显著低于首次（把两个数字都记进笔记，这是可讲的成果）

# 异常路径 ①：把某个 incident 审批驳回 → 只落 Markdown，向量库条数不增
# 异常路径 ②：把 Chroma 目录改成不可读 → search 返回 []，诊断流程照常跑完不崩
```
判定特征：真实 embedding 下 add→search 命中且排序正确；二次同类步数下降（有两个真实数字）；驳回案例不入库；检索异常不阻塞。依赖真实服务：embedding（DashScope）、M2 网关、全链路模型。

**完成判定**
- [ ] 第一条入库案例来自 M8 **真实跑出来**的报告，不是手编样例
- [ ] `KnowledgeHit` 5 字段定义完整；向量化的是症状段，结论放 metadata
- [ ] `search` 异常返回 `[]` 不抛，实测验证过
- [ ] `TriageResult` 补上 `fast_path`/`candidate_root`，阈值边界（0.849/0.851）正确
- [ ] `knowledge_match` 接在 triage **之前**，`knowledge_update` 接在 approval **之后**
- [ ] 只有 `verdict=pass` 且 `approved` 才入库，驳回只落 Markdown（实测验证过）
- [ ] 二次同类故障步数下降，两个真实数字记录在案
- [ ] lead 的快速路径措辞是"优先验证候选"而非"直接采信"

---
*版本 C 特征：知识闭环全靠"照官方格式直接写本项目 SOP → 用真跑出来的报告做第一条案例 → 自研 loader、沉淀门槛与快速路径"，让系统第二次遇到同类故障时真的更快，且这个"更快"有两个可被质证的数字。*
