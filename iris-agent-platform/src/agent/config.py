"""定义应用配置及智能体角色."""

from enum import StrEnum
from functools import cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class AgentRole(StrEnum):
    """定义事故处理工作流支持的智能体角色."""

    TRIAGE = "triage"
    INVESTIGATE = "investigate"
    VERIFY = "verify"
    REPORT = "report"


class ProviderSettings(BaseSettings):
    """定义模型供应商的通用连接配置."""

    base_url: str
    api_key: SecretStr


class AlibabaSettings(ProviderSettings):
    """定义阿里云模型供应商配置."""

    model_config = SettingsConfigDict(
        env_prefix="ALIBABA_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


class OpenRouterSettings(ProviderSettings):
    """定义 OpenRouter 模型供应商配置."""

    model_config = SettingsConfigDict(
        env_prefix="OPEN_ROUTER_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


class GatewaySettings(BaseSettings):
    """定义工具网关连接配置."""

    model_config = SettingsConfigDict(
        env_prefix="GATEWAY_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    base_url: str
    bearer_token: SecretStr


class Settings(BaseSettings):
    """汇总模型、供应商及工具网关配置."""

    model_config = SettingsConfigDict(
        env_prefix="MODEL_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    alibaba: AlibabaSettings = Field(default_factory=AlibabaSettings)
    open_router: OpenRouterSettings = Field(default_factory=OpenRouterSettings)
    gateway: GatewaySettings = Field(default_factory=GatewaySettings)

    triage: str = "anthropic/claude-sonnet-5"
    investigate: str = "anthropic/claude-sonnet-5"
    verify: str = "anthropic/claude-sonnet-5"
    report: str = "deepseek-v4-pro"

    # ---- 单次事故预算：BudgetLedger 的默认值从这里注入 ----
    token_budget: int = 200_000          # LLM token（prompt+completion）上限
    tool_call_budget: int = 30           # 网关模板通道调用上限，与 M2 网关侧 RedisService.TOOL_CALLS_LIMIT 对齐
    # ↑ 必须与网关一致：charge_tool_call 用 limit - meta.tool_calls_remaining 反推已用次数，对不上会算错
    raw_call_budget: int = 5             # 网关 raw 通道调用上限，与 M2 网关侧 raw 预算对齐，与模板通道分开计
    wall_clock_budget_seconds: int = 600  # 墙钟预算；guard.load_ledger() 用它算 deadline_ts
    max_investigate_rounds: int = 2  # investigate 回流上限，条件边判断还能不能打回时用


    subagents_enabled: bool = False  # M10 才会真正生效；M7 恒为 False，走本模块单 Agent


@cache
def get_settings() -> Settings:
    """读取并缓存应用配置."""
    return Settings()
