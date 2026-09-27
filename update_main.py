import re

with open("backend/main.py", "r", encoding="utf-8") as f:
    code = f.read()

# Update normalize_intent allowed list
code = code.replace(
    '"toggle_bluetooth",\n        "toggle_flashlight",\n        "open_settings",\n        "set_brightness",\n        "set_volume",',
    '"toggle_bluetooth",\n        "flashlight",\n        "open_settings",\n        "set_brightness",\n        "increase_brightness",\n        "decrease_brightness",\n        "set_volume",\n        "increase_volume",\n        "decrease_volume",\n        "mute_volume",'
)

# Update extract fields in normalize_intent
code = code.replace(
    'level=clean_int(data.get("level")),',
    'value=clean_int(data.get("value") or data.get("level")),\n        enabled=data.get("enabled"),'
)

# Replace the fallback_intent logic for volume
old_volume_logic = """    if re.search(r"\\b(volume|sound|loud|quiet|mute)\\b", text) and not re.search(r"\\bopen\\b.*\\b(sound|volume) settings\\b", text):
        if re.search(r"\\bopen\\b", text) and "settings" in text:
            pass
        else:
            level, state = parse_level_state(text)
            if level is None and state is None:
                level = 50
            return remember_session(
                raw,
                "Changing volume",
                [_intent_payload(
                    "set_volume",
                    level=level,
                    state=state,
                    message="Changing volume.",
                )],
            )"""

new_volume_logic = """    if re.search(r"\\b(volume|sound|loud|quiet|mute)\\b", text) and not re.search(r"\\bopen\\b.*\\b(sound|volume) settings\\b", text):
        if re.search(r"\\bopen\\b", text) and "settings" in text:
            pass
        else:
            level, state = parse_level_state(text)
            if state == "up":
                intent = "increase_volume"
            elif state == "down":
                intent = "decrease_volume"
            elif state == "mute":
                intent = "mute_volume"
            else:
                intent = "set_volume"
                level = level if level is not None else 50
                
            return remember_session(
                raw,
                "Changing volume",
                [_intent_payload(
                    intent,
                    value=level,
                    message="Changing volume.",
                )],
            )"""
code = code.replace(old_volume_logic, new_volume_logic)

# Replace the fallback_intent logic for brightness
old_bright_logic = """    if re.search(r"\\b(brightness|brighter|dimmer|screen light)\\b", text):
        if re.search(r"\\bopen\\b", text) and "settings" in text:
            pass
        else:
            level, state = parse_level_state(text)
            if level is None and state is None:
                level = 50
            return remember_session(
                raw,
                "Changing brightness",
                [_intent_payload(
                    "set_brightness",
                    level=level,
                    state=state,
                    message="Changing brightness.",
                )],
            )"""
new_bright_logic = """    if re.search(r"\\b(brightness|brighter|dimmer|screen light)\\b", text):
        if re.search(r"\\bopen\\b", text) and "settings" in text:
            pass
        else:
            level, state = parse_level_state(text)
            if state == "up":
                intent = "increase_brightness"
            elif state == "down":
                intent = "decrease_brightness"
            else:
                intent = "set_brightness"
                level = level if level is not None else 50
            return remember_session(
                raw,
                "Changing brightness",
                [_intent_payload(
                    intent,
                    value=level,
                    message="Changing brightness.",
                )],
            )"""
code = code.replace(old_bright_logic, new_bright_logic)

# Replace the fallback_intent logic for flashlight
old_flash_logic = """    if re.search(r"\\b(flashlight|torch|flash light)\\b", text):
        state = "off" if re.search(r"\\b(off|disable|stop)\\b", text) else "on"
        return remember_session(
            raw,
            "Flashlight",
            [_intent_payload(
                "toggle_flashlight",
                state=state,
                message=f"Turning flashlight {state}.",
            )],
        )"""
new_flash_logic = """    if re.search(r"\\b(flashlight|torch|flash light)\\b", text):
        state = "off" if re.search(r"\\b(off|disable|stop)\\b", text) else "on"
        return remember_session(
            raw,
            "Flashlight",
            [_intent_payload(
                "flashlight",
                enabled=(state == "on"),
                message=f"Turning flashlight {state}.",
            )],
        )"""
code = code.replace(old_flash_logic, new_flash_logic)

with open("backend/main.py", "w", encoding="utf-8") as f:
    f.write(code)

print("Updated main.py")
