import json

from sre_copilot.tools.client import GatewayRequestError
from sre_copilot.tools.definitions import make_tools


def test_gateway_request_error_becomes_degraded_tool_result() -> None:
    class FailingClient:
        def call(self, _path, _body):
            raise GatewayRequestError(400, "INVALID_PARAM", "bad request")

    result = make_tools(FailingClient())["query_cmdb"].invoke(
        {"template": "get_topology"}
    )
    payload = json.loads(result)

    assert payload["degraded"] is True
    assert payload["data"] is None
    assert "400 INVALID_PARAM" in payload["degraded_reason"]
