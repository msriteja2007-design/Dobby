import re

with open("backend/main.py", "r", encoding="utf-8") as f:
    code = f.read()

old_payload = 'def _intent_payload(intent, app=None, query=None, setting=None, state=None, level=None, message="Okay."):\n    return {\n        "intent": intent,\n        "app": app,\n        "query": query,\n        "setting": setting,\n        "state": state,\n        "level": level,\n        "message": message,\n    }'
new_payload = 'def _intent_payload(intent, app=None, query=None, setting=None, state=None, level=None, value=None, enabled=None, message="Okay."):\n    return {\n        "intent": intent,\n        "app": app,\n        "query": query,\n        "setting": setting,\n        "state": state,\n        "level": level,\n        "value": value,\n        "enabled": enabled,\n        "message": message,\n    }'

code = code.replace(old_payload, new_payload)

with open("backend/main.py", "w", encoding="utf-8") as f:
    f.write(code)

print("Updated _intent_payload")
