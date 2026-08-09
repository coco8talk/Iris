"""structlog 与 stdlib logging 的统一配置，全进程只在这里配一次.

在这之前，各模块直接 `structlog.getLogger()` 但从没有人调用过 `structlog.configure()`，
用的是 structlog 的默认惰性配置：PrintLogger 直写 stdout，完全绕开 stdlib logging。
后果是 agent 自己的事件日志和 uvicorn 的访问日志分属两套通道——级别、格式、输出目的地
各管各的，想统一改成 JSON 落文件时也没有一个总开关。

这里把 structlog 桥接到 stdlib logging：两边共用 root logger 的同一批 handler，
`LOG_LEVEL` / `LOG_JSON` 两个环境变量同时对二者生效。

本模块刻意不 import 任何项目内模块（config 也不行）：它由 agent/__init__.py 在最早期
调用，一旦引入项目依赖就会形成导入环。
"""

from __future__ import annotations

import logging
import os
import sys
from typing import Any

import structlog

_configured = False


def setup_logging(level: str | None = None, json_logs: bool | None = None) -> None:
    """配置 structlog + stdlib logging.

    参数缺省时读环境变量：LOG_LEVEL（默认 INFO）、LOG_JSON（默认关，值为
    1/true/yes 时输出 JSON 行，适合容器里给日志采集器吃）。

    幂等：uvicorn --reload 会反复重新导入模块，重复调用不能把 handler 越挂越多，
    否则同一条日志会被打印 N 遍。
    """
    global _configured
    if _configured:
        return

    if level is None:
        level = os.getenv("LOG_LEVEL", "INFO")
    if json_logs is None:
        json_logs = os.getenv("LOG_JSON", "").lower() in ("1", "true", "yes")

    # 这批处理器对两类日志都要跑：structlog 原生事件，以及 uvicorn/httpx 等
    # 第三方通过 stdlib logging 打进来的“外来”日志（foreign_pre_chain）。
    shared_processors: list[Any] = [
        structlog.contextvars.merge_contextvars,
        structlog.stdlib.add_log_level,
        structlog.stdlib.add_logger_name,
        structlog.processors.TimeStamper(fmt="iso", utc=False),
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
    ]

    structlog.configure(
        processors=[
            *shared_processors,
            structlog.stdlib.ProcessorFormatter.wrap_for_formatter,
        ],
        logger_factory=structlog.stdlib.LoggerFactory(),
        wrapper_class=structlog.stdlib.BoundLogger,
        cache_logger_on_first_use=True,
    )

    renderer: Any = (
        structlog.processors.JSONRenderer()
        if json_logs
        else structlog.dev.ConsoleRenderer(colors=sys.stderr.isatty())
    )
    formatter = structlog.stdlib.ProcessorFormatter(
        foreign_pre_chain=shared_processors,
        processors=[
            structlog.stdlib.ProcessorFormatter.remove_processors_meta,
            renderer,
        ],
    )

    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(formatter)

    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level.upper())

    # uvicorn 启动时会给自己这三个 logger 各挂一个 handler，不清掉的话
    # 每条访问日志会同时经 uvicorn 的 handler 和 root 的 handler 打印两遍。
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        uvicorn_logger = logging.getLogger(name)
        uvicorn_logger.handlers = []
        uvicorn_logger.propagate = True

    # httpx 每次网关调用都会 INFO 一行 "HTTP Request: POST ..."，与我们自己的
    # gateway_call 事件重复，压到 WARNING。
    logging.getLogger("httpx").setLevel("WARNING")

    _configured = True
