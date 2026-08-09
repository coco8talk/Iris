"""M2 Tool Gateway 的薄封装：绑定一次 incident/role，反复调用不同工具路径，拿到统一信封."""

from functools import cache
from typing import Any

import httpx
import structlog
from pydantic import BaseModel, ConfigDict, Field, SecretStr
from pydantic.alias_generators import to_camel

from agent.config import AgentRole, GatewaySettings

logger = structlog.getLogger()


@cache
def _client_settings() -> GatewaySettings:
    """缓存并返回网关客户端配置单例，避免每次实例化 GatewayClient 都重新读一遍环境变量."""
    return  GatewaySettings() # type: ignore[call-arg]  # 字段值由 pydantic-settings 在运行时从环境变量注入


class BudgetExceededError(Exception):
    """网关返回 429：当前 incident 在该通道（模板/raw）下的工具调用预算已耗尽."""


class GatewayRequestError(Exception):
    """网关返回非 429 的 4xx：请求本身有问题（参数校验失败、鉴权失败等），非预算耗尽."""


class Meta(BaseModel):
    """响应元信息：耗时、是否截断、剩余调用预算."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    elapsed_ms: int = Field(description="本次调用总耗时，单位毫秒")
    truncated: bool = Field(
        description="数据是否被截断：即数据成功拿到，但原始数据量超过展示上限，被主动裁剪后返回，用于告知 agent 当前不是全量数据"
    )
    tool_calls_remaining: int = Field(
        description="本次 incident 在当前通道（模板/raw）下的剩余调用次数，用尽后网关返回 429"
    )


class ApiEnvelope(BaseModel):
    """网关统一响应信封：ok/degraded 两个字段组合互斥，具体语义见网关侧 ApiEnvelope."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    ok: bool = Field(
        description="本次调用是否成功执行。只要流程正常走完，给出了结果，就是 true；只有真出错，例如：参数校验失败、内部异常，才是 false"
    )
    degraded: bool = Field(description="本次调用是否被降级处理。")
    degraded_reason: str | None = Field(default=None, description="降级原因，仅在 degraded=true 时有值")
    message: str | None = Field(default=None, description="错误信息，仅在 ok=false 时有值")
    data: Any = Field(default=None, description="返回的业务数据，如果 degraded=true，则可能返回空数据")
    meta: Meta | None = Field(
        default=None,
        description="元信息对象，耗时/截断/预算等附加信息，不属于业务数据本身；参数校验失败等场景可能为空",
    )


class GatewayClient(BaseModel):
    """绑定一次 incident_id/agent_role 的网关客户端，节点/工具层复用同一个实例发起多次调用."""

    incident_id: str
    agent_role: AgentRole
    bearer_token: SecretStr = Field(default_factory=lambda: _client_settings().bearer_token)
    base_url: str = Field(default_factory=lambda: _client_settings().base_url)

    def call(self, path: str, body: dict[str, Any]) -> ApiEnvelope:
        """调用网关的一个工具路径，返回解析好的 ApiEnvelope.

        429 抛 BudgetExceededError，其余 4xx 抛 GatewayRequestError，由上层节点决定如何处理;
        本方法不吞异常、不做降级判断——degraded 只在 2xx 信封里原样透传给调用方.
        """
        log = logger.bind(
            incident_id=self.incident_id,
            agent_role=self.agent_role.value,
            path=path,
        )
        try:
            response = httpx.post(
                url=self.base_url + path,
                headers={
                    "Authorization": "Bearer " + self.bearer_token.get_secret_value(),
                    "X-Incident-Id": self.incident_id,
                    "X-Agent-Role": self.agent_role.value,
                },
                json=body,
                timeout=10.0,
            )
        except httpx.RequestError:
            # 连不上/超时不属于本方法声明的两种异常，会一路冒到节点的 except 之外
            # 直接打挂整个图执行。这里先留一行，否则只能在 incident.last_error 里
            # 事后考古。注意 headers 里有 bearer token，任何时候都不要整包打出来。
            log.exception("gateway_request_error", timeout=10.0)
            raise

        if response.status_code == 429:
            log.warning("gateway_budget_exceeded", status=response.status_code)
            raise BudgetExceededError(
                f"tool call budget exceeded for incident={self.incident_id}, path={path}"
            )
        if 400 <= response.status_code < 500:
            log.warning(
                "gateway_rejected",
                status=response.status_code,
                body=response.text[:500],
            )
            raise GatewayRequestError(
                f"gateway rejected request: status={response.status_code}, path={path}, body={response.text}"
            )

        response.raise_for_status()

        envelope = ApiEnvelope.model_validate(response.json())
        # degraded 是 2xx 信封里原样透传的，HTTP 层完全看不出异常；它又直接决定
        # verify 会不会打回（verify.py 里 degraded 即判 fail），所以要单独可见。
        log.info(
            "gateway_call",
            status=response.status_code,
            ok=envelope.ok,
            degraded=envelope.degraded,
            degraded_reason=envelope.degraded_reason,
            elapsed_ms=envelope.meta.elapsed_ms if envelope.meta else None,
            truncated=envelope.meta.truncated if envelope.meta else None,
            calls_remaining=(
                envelope.meta.tool_calls_remaining if envelope.meta else None
            ),
        )
        return envelope
