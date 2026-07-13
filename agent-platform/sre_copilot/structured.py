from enum import Enum

from langchain_core.exceptions import OutputParserException
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import SystemMessage, HumanMessage
from pydantic import BaseModel, ConfigDict, Field, ValidationError


class Severity(str, Enum):
    P1 = "P1"
    P2 = "P2"
    P3 = "P3"


class RcaSummary(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    severity: Severity = Field(
        description=(
            "Incident severity: P1 = critical user-facing outage, "
            "P2 = degraded service, P3 = minor issue."
        )
    )
    suspected_domain: str = Field(
        min_length=1,
        description=(
            "Single most likely failing domain, e.g. database, network, "
            "application, infrastructure."
        ),
    )
    summary: str = Field(
        min_length=1,
        description=(
            "One or two sentences stating the suspected root cause, "
            "based only on the alert text."
        ),
    )


class StructuredOutputError(RuntimeError):
    """Raised when the model response cannot be validated as an RcaSummary."""


_SYSTEM_PROMPT = (
    "You are an SRE triage assistant. Read the alert and produce an initial "
    "RCA summary. Base every field only on the alert text; do not invent "
    "details that are not present."
)


def generate_rca_summary(model: BaseChatModel, alert_text: str) -> RcaSummary:
    """
    Triage one alert into a validated RcaSummary via structured output.

    Raises StructuredOutputError if the model response cannot be parsed
    or validated; provider errors (network, auth) propagate unchanged.
    """
    structured_model=model.with_structured_output(RcaSummary, method="function_calling")

    _messages=[
        SystemMessage(content=_SYSTEM_PROMPT),
        HumanMessage(content=alert_text)
    ]

    try:
        response=structured_model.invoke(_messages)
    except (OutputParserException, ValidationError) as e:
        raise StructuredOutputError(f"model response failed RcaSummary validation: {e}") from e
    if not isinstance(response, RcaSummary):
        raise StructuredOutputError(f"expected RcaSummary, got {type(response).__name__}")
    return response