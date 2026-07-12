# Increment 01：真实模型连接交付

## 已写入文件

- `agent-platform/pyproject.toml`
- `agent-platform/uv.lock`
- `agent-platform/.env.example`
- `agent-platform/sre_copilot/config.py`
- `agent-platform/scripts/smoke_model.py`
- `agent-platform/tests/test_config.py`

本地 `agent-platform/.env` 增加两个非敏感模型 ID；该文件已由根目录
`.gitignore` 排除，不属于交付文件。

## 核心设计

- Alibaba Model Studio 的 OpenAI-compatible endpoint 使用 `ChatOpenAI`。
- OpenRouter 使用 `ChatOpenRouter`。
- 两个平台分别使用 `BaseSettings` 和独立环境变量前缀加载配置。
- `SecretStr` 避免凭证出现在配置对象表示中。
- 配置通过缓存 getter 在进程内延迟共享；工厂仍允许显式传入设置，便于单元测试。
- 冒烟脚本只输出 provider、model、耗时、非空回复摘要和 PASS/FAIL。
- 当前增量未引入 Agent、Tool、LangGraph、FastAPI、结构化输出或重试框架。

## 验收证据

- `uv run pytest -q`：7 passed。
- 两个平台真实 `invoke`：均返回非空回复并输出 PASS。
- 两个平台使用错误凭证：均输出明确认证失败，进程退出码为 1。
- `.env` 经 `git check-ignore -v .env` 确认被忽略。
- 目标代码和日志凭证扫描通过。

## 未解决问题

- `docs/dependency-baseline.md` 尚不存在，依赖版本目前由 `uv.lock` 固定，但尚未形成项目级依赖基线文档。
- 当前包位于 `agent-platform/sre_copilot/`，未迁移到执行计划中的 `src/` 布局；冒烟脚本使用最小路径引导以支持指定执行命令。
- 生产部署仍需集中密钥管理、可观测性、限流、超时策略和密钥轮换；这些不属于本增量。

## Prompt 02 前置条件

- 两个平台的真实 API 连通性已确认。
- `api_key`、`base_url`、`model` 均由环境配置提供。
- 模型工厂返回 LangChain `BaseChatModel`，可供下一增量复用。
- Prompt 02 必须继续遵守当前任务定义的全部红线，不得提前实现后续框架。
