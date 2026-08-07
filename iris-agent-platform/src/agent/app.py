import json

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

app = FastAPI()


@app.post("/webhook/alertmanager")
async def webhook(request: Request) -> JSONResponse:
    body = await request.json()
    print(json.dumps(body, indent=2, ensure_ascii=False))
    with open("am-fixture.json", "w") as file:
        json.dump(body, file, indent=2, ensure_ascii=False)
    return JSONResponse({"ok": True})
