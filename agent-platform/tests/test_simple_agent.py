import pytest

from sre_copilot.agents import simple_agent


def test_build_simple_agent_exposes_all_gateway_tools(monkeypatch) -> None:
    tools = {"query_cmdb": object(), "query_metrics": object()}
    captured = {}
    built_agent = object()

    monkeypatch.setattr(simple_agent, "make_tools", lambda _client: tools)

    def fake_create_agent(**kwargs):
        captured.update(kwargs)
        return built_agent

    monkeypatch.setattr(simple_agent, "create_agent", fake_create_agent)

    result = simple_agent.build_simple_agent(model=object(), client=object())

    assert result is built_agent
    assert captured["tools"] == list(tools.values())
    assert captured["name"] == "simple-agent"
    assert captured["middleware"] == []
    assert "自主判断" in captured["system_prompt"]


def test_build_simple_agent_accepts_injected_tools(monkeypatch) -> None:
    injected_tools = [object(), object()]
    captured = {}

    monkeypatch.setattr(
        simple_agent,
        "create_agent",
        lambda **kwargs: captured.update(kwargs) or object(),
    )

    simple_agent.build_simple_agent(
        model=object(),
        tools=injected_tools,
        system_prompt="investigate",
        name="investigator",
        tool_call_limits={"query_metrics": 3},
        model_call_limit=8,
    )

    assert captured["tools"] == injected_tools
    assert captured["system_prompt"] == "investigate"
    assert captured["name"] == "investigator"
    assert len(captured["middleware"]) == 2
    assert captured["middleware"][0].tool_name == "query_metrics"
    assert captured["middleware"][0].run_limit == 3
    assert captured["middleware"][1].run_limit == 8


def test_build_simple_agent_rejects_ambiguous_tool_sources() -> None:
    with pytest.raises(ValueError, match="either client or tools"):
        simple_agent.build_simple_agent(
            model=object(),
            client=object(),
            tools=[object()],
        )
