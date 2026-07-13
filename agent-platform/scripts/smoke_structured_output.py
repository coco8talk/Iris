import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from sre_copilot.config import create_alibaba_model, create_openrouter_model
from sre_copilot.structured import generate_rca_summary

ALERT_TEXT = """\
[FIRING] HighErrorRate
service: checkout-service
description: 5xx error ratio reached 34% over the last 5 minutes (threshold 5%).
detail: downstream MySQL instance db-payments-01 reports connection pool \
exhausted (200/200 in use); checkout requests time out after 10s.
started_at: 2026-07-12T09:14:00Z
"""


def run_smoke(provider, create_model) -> bool:
    started = time.perf_counter()
    model_name = ""

    try:
        model = create_model()
        model_name = model.model_name
        result = generate_rca_summary(model, ALERT_TEXT)
        elapsed_ms = round((time.perf_counter() - started) * 1000)
        print(
            f"PASS provider={provider} model={model_name} "
            f"elapsed_ms={elapsed_ms} result={result.model_dump(mode='json')}"
        )
        return True
    except Exception as exc:
        elapsed_ms = round((time.perf_counter() - started) * 1000)
        message = " ".join(str(exc).split())[:200]
        print(
            f"FAIL provider={provider} model={model_name} "
            f'elapsed_ms={elapsed_ms} error={type(exc).__name__} message="{message}"',
            file=sys.stderr,
        )
        return False


def main() -> int:
    results = [
        run_smoke("alibaba", create_alibaba_model),
        run_smoke("openrouter", create_openrouter_model),
    ]
    return 0 if all(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
