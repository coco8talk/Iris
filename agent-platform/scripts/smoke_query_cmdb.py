import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from langchain_core.messages import HumanMessage

from sre_copilot.config import create_alibaba_model
from sre_copilot.tools.client import GatewayClient
from sre_copilot.tools.definitions import make_tools

_MAX_ROUNDS = 6

_PROMPT = (
    "order-service 疑似出现故障。请先查清系统的服务依赖拓扑，"
    "再查 order-service 的详情，最后用中文简要总结："
    "order-service 依赖哪些服务和中间件、由谁负责、部署在哪个端口。"
)


def check_model_gate() -> bool:
    """红线-5 门禁:先确认真实模型凭证仍有效,再打真实 Gateway。"""
    started = time.perf_counter()
    try:
        model = create_alibaba_model()
        response = model.invoke(
            [HumanMessage(content="Reply with the single word OK.")]
        )
        if not response.text.strip():
            raise RuntimeError("empty model response")
        elapsed_ms = round((time.perf_counter() - started) * 1000)
        print(
            f"PASS gate=model provider=alibaba "
            f"model={model.model_name} elapsed_ms={elapsed_ms}"
        )
        return True
    except Exception as exc:
        print(f"FAIL gate=model error={type(exc).__name__}", file=sys.stderr)
        return False


def check_llm_query_cmdb(incident_id: str) -> bool:
    """真实模型 + 真实 Gateway:由模型自主决定调用 query_cmdb,验证完整工具链路。"""
    started = time.perf_counter()
    client = GatewayClient(incident_id=incident_id, agent_role="lead")
    tool = make_tools(client)["query_cmdb"]
    llm = create_alibaba_model().bind_tools([tool])
    try:
        messages = [HumanMessage(content=_PROMPT)]
        tool_call_count = 0
        for _ in range(_MAX_ROUNDS):
            response = llm.invoke(messages)
            messages.append(response)
            if not response.tool_calls:
                break
            for tool_call in response.tool_calls:
                tool_call_count += 1
                print(f'tool_call name={tool_call["name"]} args={tool_call["args"]}')
                messages.append(tool.invoke(tool_call))
        else:
            raise RuntimeError(f"no final answer within {_MAX_ROUNDS} rounds")

        if tool_call_count == 0:
            raise RuntimeError("model answered without calling query_cmdb")
        final_text = response.text.strip()
        if not final_text:
            raise RuntimeError("empty final answer")

        elapsed_ms = round((time.perf_counter() - started) * 1000)
        print(
            f"PASS tool=query_cmdb tool_calls={tool_call_count} "
            f"elapsed_ms={elapsed_ms}"
        )
        print(f"--- model final answer ---\n{final_text}")
        return True
    except Exception as exc:
        print(
            f"FAIL tool=query_cmdb error={type(exc).__name__}: {exc}",
            file=sys.stderr,
        )
        return False
    finally:
        client.close()


def main() -> int:
    if not check_model_gate():
        return 1
    incident_id = f"smoke-{uuid.uuid4().hex[:8]}"
    print(f"incident_id={incident_id}")
    return 0 if check_llm_query_cmdb(incident_id) else 1


if __name__ == "__main__":
    raise SystemExit(main())
