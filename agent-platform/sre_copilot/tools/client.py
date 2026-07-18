from pathlib import Path
from typing import Any, Literal

import httpx
from pydantic import AnyHttpUrl, BaseModel, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

# Anchor to the project directory so scripts work from any cwd.
_ENV_FILE = Path(__file__).resolve().parents[2] / ".env"

AgentRole = Literal["lead", "metrics", "logs", "trace", "verifier"]


class GatewaySettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=_ENV_FILE,
        env_prefix="TOOL_GATEWAY_",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    base_url: AnyHttpUrl
    token: SecretStr


class Meta(BaseModel):
    elapsed_ms: int
    truncated: bool
    budget_remaining: int


class Envelope(BaseModel):
    ok: bool
    degraded: bool
    degraded_reason: str | None
    data: dict[str, Any] | None
    meta: Meta


class GatewayRequestError(Exception):
    """4xx from the gateway, carrying the contract's stable error code."""

    def __init__(self, status_code: int, code: str, message: str) -> None:
        super().__init__(f"{status_code} {code}: {message}")
        self.status_code = status_code
        self.code = code
        self.message = message


class BudgetExceededError(GatewayRequestError):
    """429 BUDGET_EXCEEDED — caught by the budget guard upstream (H5)."""


class GatewayClient:
    def __init__(
            self,
            incident_id: str,
            agent_role: AgentRole,
            settings: GatewaySettings | None = None,
    ) -> None:
        settings = settings or GatewaySettings()
        self._http = httpx.Client(
            base_url=str(settings.base_url),
            headers={
                "Authorization": f"Bearer {settings.token.get_secret_value()}",
                "X-Incident-Id": incident_id,
                "X-Agent-Role": agent_role,
            },
            timeout=10.0,
        )

    def call(self, path: str, body: dict[str, Any]) -> Envelope:
        response = self._http.post(path, json=body)
        if 400 <= response.status_code < 500:
            try:
                error = response.json()
            except ValueError:
                error = {}
            error_cls = (
                BudgetExceededError
                if response.status_code == 429
                else GatewayRequestError
        )
            raise error_cls(
                response.status_code,
                error.get("code", "UNKNOWN"),
                error.get("message", response.text[:200]),
        )

        response.raise_for_status()
        return Envelope.model_validate(response.json())


    def close(self) -> None:
        self._http.close()
