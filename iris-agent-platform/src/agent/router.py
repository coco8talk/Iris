"""根据智能体角色选择聊天模型."""

from langchain_core.language_models import BaseChatModel
from langchain_deepseek import ChatDeepSeek
from langchain_openrouter import ChatOpenRouter

from .config import AgentRole, ProviderSettings, get_settings

_ROLE_PROVIDER: dict[AgentRole, str] = {
    AgentRole.TRIAGE: "open_router",
    AgentRole.INVESTIGATE: "open_router",
    AgentRole.VERIFY: "open_router",
    AgentRole.REPORT: "alibaba",
}


def get_model(role: AgentRole | str) -> BaseChatModel:
    """返回指定智能体角色使用的聊天模型."""
    agent_role = AgentRole(role)
    settings = get_settings()
    provider_name = _ROLE_PROVIDER[agent_role]
    provider: ProviderSettings = getattr(settings, provider_name)
    model_name: str = getattr(settings, agent_role.value)

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
    )
