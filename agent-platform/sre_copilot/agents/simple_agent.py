from collections.abc import Iterable, Mapping

from langchain.agents import create_agent
from langchain.agents.middleware import ModelCallLimitMiddleware, ToolCallLimitMiddleware
from langchain.agents.structured_output import ToolStrategy
from langchain_core.language_models import BaseChatModel
from langchain_core.tools import BaseTool
from pydantic import BaseModel, Field

from sre_copilot.tools.client import GatewayClient
from sre_copilot.tools.definitions import make_tools


class AgentAnswer(BaseModel):
    """agent 的最终响应结构"""

    answer: str = Field(min_length=1, description="对用户问题的中文回答")

    info_complete: bool = Field(
        description="信息是否完整：工具返回 degraded=true、data 为空或调用失败时必须为 false"
    )

    caveat: str | None = Field(
        default=None,
        description="信息不完整时说明缺了什么及原因；信息完整时为 null"
    )


_SYSTEM_PROMPT = (
    "你是 SRE 值班助手，负责回答与生产系统相关的问题。\n"
    "行为规则：\n"
    "1. 根据问题和工具描述，自主判断是否需要调用工具以及调用哪些工具；"
    "只调用回答问题所必需的最小工具集合。\n"
    "2. 涉及当前系统事实、运行状态或故障的问题，必须以工具返回的数据为依据，"
    "禁止凭记忆猜测；与具体系统无关的通用概念问题可以直接回答。\n"
    "3. 工具返回 degraded=true、data 为空或调用失败时，如实说明信息不完整："
    "info_complete 置为 false，并在 caveat 中说明缺失原因，禁止编造数据。\n"
    "4. 不要用相同或等价参数重复失败/降级的查询；信息不足时直接给出有限结论。"
)


def build_simple_agent(
    model: BaseChatModel,
    client: GatewayClient | None = None,
    *,
    tools: Iterable[BaseTool] | None = None,
    system_prompt: str = _SYSTEM_PROMPT,
    response_schema: type[BaseModel] = AgentAnswer,
    name: str = "simple-agent",
    tool_call_limits: Mapping[str, int] | None = None,
    model_call_limit: int | None = None,
):
    """构建工具调用 Agent；工具选择由模型根据 prompt 和工具描述自主完成。"""
    if tools is None:
        if client is None:
            raise ValueError("client is required when tools are not provided")
        tools = make_tools(client).values()
    elif client is not None:
        raise ValueError("provide either client or tools, not both")

    middleware = [
        ToolCallLimitMiddleware(
            tool_name=tool_name,
            run_limit=limit,
            exit_behavior="continue",
        )
        for tool_name, limit in (tool_call_limits or {}).items()
    ]
    if model_call_limit is not None:
        middleware.append(
            ModelCallLimitMiddleware(
                run_limit=model_call_limit,
                exit_behavior="end",
            )
        )

    return create_agent(
        system_prompt=system_prompt,
        model=model,
        name=name,
        tools=list(tools),
        response_format=ToolStrategy(response_schema),
        middleware=middleware,
    )
