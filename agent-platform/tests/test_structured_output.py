import json

import pytest
from langchain_core.language_models.fake_chat_models import FakeListChatModel
from langchain_core.runnables import RunnableLambda
from pydantic import ValidationError

from sre_copilot.structured import (
    RcaSummary,
    Severity,
    StructuredOutputError,
    generate_rca_summary,
)

VALID_PAYLOAD = {
    "severity": "P1",
    "suspected_domain": "database",
    "summary": "Checkout 5xx spike caused by an exhausted MySQL connection pool.",
}


class FakeStructuredChatModel(FakeListChatModel):
    """FakeListChatModel cannot bind tools, so emulate with_structured_output
    by parsing the queued response text through the schema — same validation
    path as the real integration."""

    def with_structured_output(self, schema, **_kwargs):
        def _invoke(messages):
            message = self.invoke(messages)
            return schema.model_validate_json(message.content)

        return RunnableLambda(_invoke)


def fake_model(payload: dict) -> FakeStructuredChatModel:
    return FakeStructuredChatModel(responses=[json.dumps(payload)])


def test_valid_response_returns_validated_summary() -> None:
    result = generate_rca_summary(fake_model(VALID_PAYLOAD), "alert text")

    assert isinstance(result, RcaSummary)
    assert result.severity is Severity.P1
    assert result.suspected_domain == "database"
    assert result.summary == VALID_PAYLOAD["summary"]


@pytest.mark.parametrize(
    "payload",
    [
        pytest.param(
            {k: v for k, v in VALID_PAYLOAD.items() if k != "severity"},
            id="missing-severity",
        ),
        pytest.param({**VALID_PAYLOAD, "severity": "P0"}, id="invalid-enum"),
        pytest.param({**VALID_PAYLOAD, "summary": ""}, id="empty-summary"),
        pytest.param({**VALID_PAYLOAD, "summary": "   "}, id="whitespace-summary"),
    ],
)
def test_invalid_response_raises_structured_output_error(payload: dict) -> None:
    with pytest.raises(StructuredOutputError, match="RcaSummary"):
        generate_rca_summary(fake_model(payload), "alert text")


def test_none_result_raises_structured_output_error() -> None:
    class NoResultModel(FakeStructuredChatModel):
        def with_structured_output(self, schema, **_kwargs):
            return RunnableLambda(lambda _messages: None)

    with pytest.raises(StructuredOutputError, match="expected RcaSummary"):
        generate_rca_summary(NoResultModel(responses=["unused"]), "alert text")


def test_schema_rejects_missing_field_directly() -> None:
    with pytest.raises(ValidationError, match="suspected_domain"):
        RcaSummary.model_validate({"severity": "P2", "summary": "x"})