"""调试专用入口：IDE 直接 Debug 这个文件，不走 `uv run uvicorn --reload`.

--reload 会额外 fork 一个子进程跑真正的 app，IDE 调试器默认只挂在你点 Debug 的
那个进程上，附不上子进程，断点不会命中；这里直接单进程跑，调试器和被调试代码
是同一个进程，断点必中。
"""

import uvicorn

if __name__ == "__main__":
    uvicorn.run("agent.app:app", host="0.0.0.0", port=8000, reload=False)
