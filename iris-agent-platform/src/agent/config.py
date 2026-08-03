"""定义应用配置及智能体角色."""

from enum import StrEnum

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
