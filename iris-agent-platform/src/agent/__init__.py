"""

"""

# 必须在导入任何会打日志的模块之前配置好，否则那些模块在 import 期打的日志
# 会走 structlog 的默认配置（绕开 stdlib logging），格式和目的地都不对。
from agent.logging_setup import setup_logging

setup_logging()

from agent.graph import graph  # noqa: E402

__all__ = ["graph", "setup_logging"]
