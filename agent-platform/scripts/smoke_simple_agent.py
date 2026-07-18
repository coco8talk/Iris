import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from langchain_core.messages import AIMessage, HumanMessage

from sre_copilot.agents.simple_agent import AgentAnswer, build_simple_agent
from sre_copilot.config import create_alibaba_model, create_openrouter_model
from sre_copilot.tools.client import GatewayClient

_FACTUAL_QUESTION = "payment-service 依赖哪些服务和中间件？"
_CONCEPT_QUESTION = "什么是熔断？简要解释即可。"


def _count_query_cmdb_calls(messages: list) -> int:
    return sum(
        1
        for message in messages
        if isinstance(message, AIMessage)
        for tool_call in message.tool_calls
        if tool_call["name"] == "query_cmdb"
    )


def check_model_gate() -> bool:
    """红线-5 门禁:先确认真实模型凭证仍有效,再打真实 Gateway。"""
    started = time.perf_counter()
    try:
        model = create_openrouter_model()
        response = model.invoke([HumanMessage(content="Reply with the single word OK.")])
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


def _run_agent_check(
    name: str,
    incident_id: str,
    question: str,
    expect_tool_call: bool,
) -> bool:
    started = time.perf_counter()
    client = GatewayClient(incident_id=incident_id, agent_role="lead")
    try:
        agent = build_simple_agent(create_openrouter_model(), client)
        result = agent.invoke({"messages": [HumanMessage(content=question)]})

        tool_calls = _count_query_cmdb_calls(result["messages"])
        if expect_tool_call and tool_calls == 0:
            raise RuntimeError("factual question answered without calling query_cmdb")
        if not expect_tool_call and tool_calls > 0:
            raise RuntimeError(f"concept question triggered {tool_calls} tool call(s)")

        reply = result["structured_response"]
        if not isinstance(reply, AgentAnswer):
            raise RuntimeError(f"expected AgentAnswer, got {type(reply).__name__}")

        print(result)

        elapsed_ms = round((time.perf_counter() - started) * 1000)
        print(
            f"PASS check={name} tool_calls={tool_calls} "
            f"info_complete={reply.info_complete} elapsed_ms={elapsed_ms}"
        )
        print(f"--- answer ---\n{reply.answer}")
        if reply.caveat:
            print(f"--- caveat ---\n{reply.caveat}")
        return True
    except Exception as exc:
        print(f"FAIL check={name} error={type(exc).__name__}: {exc}", file=sys.stderr)
        return False
    finally:
        client.close()


def main() -> int:
    if not check_model_gate():
        return 1
    incident_id = f"smoke-{uuid.uuid4().hex[:8]}"
    print(f"incident_id={incident_id}")
    ok = _run_agent_check("factual", incident_id, _FACTUAL_QUESTION, expect_tool_call=True)
    ok = _run_agent_check("concept", incident_id, _CONCEPT_QUESTION, expect_tool_call=False) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())