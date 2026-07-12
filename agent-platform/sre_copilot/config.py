from functools import lru_cache

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_openai import ChatOpenAI
from langchain_openrouter import ChatOpenRouter
from pydantic import AnyHttpUrl, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class AlibabaSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="ALIBABA_",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    api_key: SecretStr
    base_url: AnyHttpUrl
    model: str


class OpenRouterSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="OPENROUTER_",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    api_key: SecretStr
    base_url: AnyHttpUrl
    model: str


@lru_cache(maxsize=1)
def get_alibaba_settings() -> AlibabaSettings:
    return AlibabaSettings()


@lru_cache(maxsize=1)
def get_openrouter_settings() -> OpenRouterSettings:
    return OpenRouterSettings()


def create_alibaba_model(
    settings: AlibabaSettings | None = None,
) -> BaseChatModel:
    settings = settings or get_alibaba_settings()
    return ChatOpenAI(
        api_key=settings.api_key,
        base_url=str(settings.base_url),
        model=settings.model,
    )


def create_openrouter_model(
    settings: OpenRouterSettings | None = None,
) -> BaseChatModel:
    settings = settings or get_openrouter_settings()
    return ChatOpenRouter(
        api_key=settings.api_key,
        base_url=str(settings.base_url),
        model=settings.model,
    )
