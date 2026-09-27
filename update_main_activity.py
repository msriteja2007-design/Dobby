import re

with open("android/app/src/main/java/com/yogitha/dobby/MainActivity.kt", "r", encoding="utf-8") as f:
    code = f.read()

old_cases = """        "toggle_flashlight" -> toggleFlashlight(context, jsonText(intentJson, "state"))
        "open_settings" -> openPhoneSetting(context, jsonText(intentJson, "setting"))
        "set_brightness" -> setBrightness(context, optionalLevel(intentJson), jsonText(intentJson, "state"))
        "set_volume" -> setVolume(context, optionalLevel(intentJson), jsonText(intentJson, "state"))"""

new_cases = """        "flashlight" -> toggleFlashlight(context, if (intentJson.optBoolean("enabled", true)) "on" else "off")
        "open_settings" -> openPhoneSetting(context, jsonText(intentJson, "setting"))
        "set_brightness" -> setBrightness(context, optionalValue(intentJson), "")
        "increase_brightness" -> setBrightness(context, null, "up")
        "decrease_brightness" -> setBrightness(context, null, "down")
        "set_volume" -> setVolume(context, optionalValue(intentJson), "")
        "increase_volume" -> setVolume(context, null, "up")
        "decrease_volume" -> setVolume(context, null, "down")
        "mute_volume" -> setVolume(context, null, "mute")"""

code = code.replace(old_cases, new_cases)

old_optional_level = """private fun optionalLevel(intentJson: JSONObject): Int? {
    if (!intentJson.has("level") || intentJson.isNull("level")) return null
    return intentJson.optInt("level")
}"""
new_optional_level = """private fun optionalValue(intentJson: JSONObject): Int? {
    if (intentJson.has("value") && !intentJson.isNull("value")) return intentJson.optInt("value")
    if (intentJson.has("level") && !intentJson.isNull("level")) return intentJson.optInt("level")
    return null
}"""
code = code.replace(old_optional_level, new_optional_level)

with open("android/app/src/main/java/com/yogitha/dobby/MainActivity.kt", "w", encoding="utf-8") as f:
    f.write(code)

print("Updated MainActivity.kt")
