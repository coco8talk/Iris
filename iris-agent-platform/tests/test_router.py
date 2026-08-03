from pprint import pprint

from agent.config import AgentRole
from agent.router import get_model

def test_get_model():
    model = get_model(AgentRole.TRIAGE)
    response = model.invoke("你好，我饿了")
    pprint(response.model_dump_json(indent=2, ensure_ascii=False))