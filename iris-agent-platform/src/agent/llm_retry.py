"""LLM 调用的传输层重试.

OpenRouter SDK 自带 retry_config，但它只让 httpx.ConnectError / httpx.TimeoutException
逃出 PermanentError 参与重试（见 openrouter/utils/retries.py 的 retry_async），
其余异常一律 `raise PermanentError(e)` → `raise exception.inner`，一次都不重试。
RemoteProtocolError（对端在返回响应头之前关掉连接，代理 idle 超时的典型表现）
正好落在这个盲区里，只能在应用层自己兜。
"""

from __future__ import annotations

import asyncio
from typing import Any, Sequence

import httpx
import structlog
from langchain_core.messages import BaseMessage
from langchain_core.runnables import Runnable, RunnableConfig

logger = structlog.getLogger()

# 只重试「连接层面没谈成」的错误：这类错误意味着请求多半没被真正处理，重发是安全的。
# 不要往里加 HTTP 4xx/5xx——那些已经有响应了，重发的语义完全不同，也可能重复计费。
TRANSPORT_ERRORS: tuple[type[Exception], ...] = (
    httpx.RemoteProtocolError,
    httpx.ConnectError,
    httpx.ReadError,
    httpx.ReadTimeout,
    httpx.WriteError,
    httpx.PoolTimeout,
)

DEFAULT_ATTEMPTS = 3


async def ainvoke_with_retry(
    model: Runnable,
    messages: Sequence[BaseMessage],
    *,
    config: RunnableConfig | None = None,
    attempts: int = DEFAULT_ATTEMPTS,
    incident_id: str | None = None,
    agent_role: str | None = None,
) -> Any:
    """调用 model.ainvoke，对传输层错误做指数退避重试.

    重试只包住这一次 LLM 调用，不要上升成 LangGraph 的节点级 RetryPolicy：
    节点重试会把整个节点重跑一遍，失败那次已经烧掉的 token 因为 state 回滚
    不会计进 BudgetLedger，预算会被系统性低估。
    """
    for attempt in range(attempts):
        try:
            return await model.ainvoke(list(messages), config=config)
        except TRANSPORT_ERRORS as e:
            if attempt == attempts - 1:
                logger.warning(
                    "llm_transport_giveup",
                    incident_id=incident_id,
                    agent_role=agent_role,
                    attempts=attempts,
                    error_type=type(e).__name__,
                    error=str(e),
                )
                raise
            backoff = 2**attempt
            logger.warning(
                "llm_transport_retry",
                incident_id=incident_id,
                agent_role=agent_role,
                attempt=attempt + 1,
                backoff_seconds=backoff,
                error_type=type(e).__name__,
                error=str(e),
            )
            await asyncio.sleep(backoff)
