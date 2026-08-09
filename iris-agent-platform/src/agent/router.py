"""根据智能体角色选择聊天模型."""

import structlog
from langchain_core.language_models import BaseChatModel
from langchain_deepseek import ChatDeepSeek
from langchain_openrouter import ChatOpenRouter

from .config import AgentRole, ProviderSettings, get_settings

logger = structlog.getLogger()

_ROLE_PROVIDER: dict[AgentRole, str] = {
    AgentRole.TRIAGE: "open_router",
    AgentRole.INVESTIGATE: "open_router",
    AgentRole.VERIFY: "open_router",
    AgentRole.REPORT: "alibaba",
}

# 这三个参数是一组的，别单独删任何一个：
# - streaming：非流式请求在模型推理期间链路上一个字节都不流动，中间代理按 idle
#   超时（常见 180s）会静默切断连接，表现为 httpx.RemoteProtocolError。开流式后
#   OpenRouter 会周期发 `: OPENROUTER PROCESSING` 保活注释，连接不再空闲。
# - timeout：单位毫秒。不传的话 SDK 会把 timeout=None 交给 httpx.build_request，
#   而 httpx 里显式 None 是「关闭超时」而不是「用客户端默认值」（那个要传
#   USE_CLIENT_DEFAULT 哨兵），结果是请求永不超时、只能干等对端踢连接。
# - max_retries：SDK 的重试只认 ConnectError/TimeoutException，所以必须先有
#   timeout 把卡死变成 ReadTimeout，这里的重试次数才真的会生效。
_OPEN_ROUTER_KWARGS: dict[str, object] = {
    "streaming": True,
    "timeout": 90_000,
    "max_retries": 3,
}


def get_model(role: AgentRole | str) -> BaseChatModel:
    """返回指定智能体角色使用的聊天模型."""
    agent_role = AgentRole(role)
    settings = get_settings()
    provider_name = _ROLE_PROVIDER[agent_role]
    provider: ProviderSettings = getattr(settings, provider_name)
    model_name: str = getattr(settings, agent_role.value)

    # 只在建图时调用（每个角色一次），量很小；M12 做消融实验换模型时，
    # 这一行是确认“到底真的跑的哪个模型”的唯一凭据。
    logger.info(
        "model_resolved",
        agent_role=agent_role.value,
        provider=provider_name,
        model=model_name,
    )

    if provider_name == "alibaba":
        return ChatDeepSeek(
            model=model_name,
            api_key=provider.api_key,
            base_url=provider.base_url,
        )

    return ChatOpenRouter(
        model=model_name,
        api_key=provider.api_key,
        base_url=provider.base_url,
        **_OPEN_ROUTER_KWARGS,
    )
