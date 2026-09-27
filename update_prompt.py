import re

with open("backend/main.py", "r", encoding="utf-8") as f:
    code = f.read()

old_prompt = """- set_brightness: change brightness level 0-100, or state up/down.
- set_volume: change media volume 0-100, or state up/down/mute.
- toggle_flashlight: turn on/off."""
new_prompt = """- set_brightness: change brightness level 0-100. Use "value".
- increase_brightness: increase brightness.
- decrease_brightness: decrease brightness.
- set_volume: change media volume 0-100. Use "value".
- increase_volume: increase media volume.
- decrease_volume: decrease media volume.
- mute_volume: mute media volume.
- flashlight: turn on/off. Use "enabled" (true/false)."""
code = code.replace(old_prompt, new_prompt)

old_schema_intents = '"toggle_wifi" | "toggle_bluetooth" | "toggle_flashlight" | "open_settings" | "set_brightness" | "set_volume" | "question"'
new_schema_intents = '"toggle_wifi" | "toggle_bluetooth" | "flashlight" | "open_settings" | "set_brightness" | "increase_brightness" | "decrease_brightness" | "set_volume" | "increase_volume" | "decrease_volume" | "mute_volume" | "question"'
code = code.replace(old_schema_intents, new_schema_intents)

old_schema_level = '"level": "integer 0-100 or null",'
new_schema_level = '"value": "integer 0-100 or null",\n      "enabled": "boolean or null",'
code = code.replace(old_schema_level, new_schema_level)

with open("backend/main.py", "w", encoding="utf-8") as f:
    f.write(code)

print("Updated prompt in main.py")
