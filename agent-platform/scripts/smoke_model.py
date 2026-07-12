import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from langchain_core.messages import HumanMessage

from sre_copilot.config import (
    create_alibaba_model,
    create_openrouter_model,
    get_alibaba_settings,
    get_openrouter_settings,
)


def run_smoke(provider, load_settings, create_model) -> bool:
    started = time.perf_counter()
    model_name = "<unconfigured>"

    try:
        settings = load_settings()
        model_name = settings.model
        model = create_model(settings)
        response = model.invoke(
            [HumanMessage(content="Reply with one short sentence confirming connection.")]
        )
        text = response.text.strip()
        if not text:
            raise RuntimeError("empty model response")

        elapsed_ms = round((time.perf_counter() - started) * 1000)
        summary = " ".join(text.split())[:120]
        print(
            f'PASS provider={provider} model={model_name} '
            f'elapsed_ms={elapsed_ms} response="{summary}"'
        )
        return True
    except Exception as exc:
        elapsed_ms = round((time.perf_counter() - started) * 1000)
        print(
            f"FAIL provider={provider} model={model_name} "
            f"elapsed_ms={elapsed_ms} error={type(exc).__name__}",
            file=sys.stderr,
        )
        return False


def main() -> int:
    results = [
        run_smoke("alibaba", get_alibaba_settings, create_alibaba_model),
        run_smoke("openrouter", get_openrouter_settings, create_openrouter_model),
    ]
    return 0 if all(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
