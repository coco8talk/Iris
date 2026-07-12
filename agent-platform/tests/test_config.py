import pytest
from pydantic import ValidationError
from pydantic_settings import BaseSettings

from sre_copilot import config
from sre_copilot.config import AlibabaSettings, OpenRouterSettings


def test_provider_settings_use_pydantic_settings() -> None:
    assert issubclass(AlibabaSettings, BaseSettings)
    assert issubclass(OpenRouterSettings, BaseSettings)


def test_alibaba_settings_load_from_environment(monkeypatch) -> None:
    monkeypatch.setenv("ALIBABA_API_KEY", "alibaba-test-key")
    monkeypatch.setenv("ALIBABA_BASE_URL", "https://alibaba.example/v1")
    monkeypatch.setenv("ALIBABA_MODEL", "alibaba-test-model")

    settings = AlibabaSettings(_env_file=None)

    assert settings.api_key.get_secret_value() == "alibaba-test-key"
    assert str(settings.base_url) == "https://alibaba.example/v1"
    assert settings.model == "alibaba-test-model"
    assert "alibaba-test-key" not in repr(settings)


def test_openrouter_settings_load_from_environment(monkeypatch) -> None:
    monkeypatch.setenv("OPENROUTER_API_KEY", "openrouter-test-key")
    monkeypatch.setenv("OPENROUTER_BASE_URL", "https://openrouter.example/v1")
    monkeypatch.setenv("OPENROUTER_MODEL", "openrouter-test-model")

    settings = OpenRouterSettings(_env_file=None)

    assert settings.api_key.get_secret_value() == "openrouter-test-key"
    assert str(settings.base_url) == "https://openrouter.example/v1"
    assert settings.model == "openrouter-test-model"
    assert "openrouter-test-key" not in repr(settings)


@pytest.mark.parametrize(
    ("settings_class", "prefix"),
    [
        (AlibabaSettings, "ALIBABA"),
        (OpenRouterSettings, "OPENROUTER"),
    ],
)
def test_settings_reject_missing_api_key(
    monkeypatch,
    settings_class,
    prefix: str,
) -> None:
    monkeypatch.delenv(f"{prefix}_API_KEY", raising=False)
    monkeypatch.setenv(f"{prefix}_BASE_URL", "https://example.com/v1")
    monkeypatch.setenv(f"{prefix}_MODEL", "test-model")

    with pytest.raises(ValidationError, match="api_key"):
        settings_class(_env_file=None)


def test_alibaba_factory_passes_expected_parameters(monkeypatch) -> None:
    captured = {}
    sentinel = object()

    def fake_chat_openai(**kwargs):
        captured.update(kwargs)
        return sentinel

    monkeypatch.setattr(config, "ChatOpenAI", fake_chat_openai)
    settings = AlibabaSettings(
        api_key="alibaba-test-key",
        base_url="https://alibaba.example/v1",
        model="alibaba-test-model",
        _env_file=None,
    )

    assert config.create_alibaba_model(settings) is sentinel
    assert captured == {
        "api_key": settings.api_key,
        "base_url": "https://alibaba.example/v1",
        "model": "alibaba-test-model",
    }


def test_openrouter_factory_passes_expected_parameters(monkeypatch) -> None:
    captured = {}
    sentinel = object()

    def fake_chat_openrouter(**kwargs):
        captured.update(kwargs)
        return sentinel

    monkeypatch.setattr(config, "ChatOpenRouter", fake_chat_openrouter)
    settings = OpenRouterSettings(
        api_key="openrouter-test-key",
        base_url="https://openrouter.example/v1",
        model="openrouter-test-model",
        _env_file=None,
    )

    assert config.create_openrouter_model(settings) is sentinel
    assert captured == {
        "api_key": settings.api_key,
        "base_url": "https://openrouter.example/v1",
        "model": "openrouter-test-model",
    }
