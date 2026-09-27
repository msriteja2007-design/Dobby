with open("android/app/src/main/java/com/yogitha/dobby/MainActivity.kt", "r", encoding="utf-8") as f:
    code = f.read()

code = code.replace("val brightness = (targetPct * 255) / 100", "val brightness = (targetPct / 100f * 255).toInt().coerceIn(0, 255)")
code = code.replace("level != null -> (level.coerceIn(0, 100) * maxVolume) / 100", "level != null -> (maxVolume * level.coerceIn(0, 100) / 100f).toInt().coerceIn(0, maxVolume)")
code = code.replace("AudioManager.FLAG_SHOW_UI", "0")

with open("android/app/src/main/java/com/yogitha/dobby/MainActivity.kt", "w", encoding="utf-8") as f:
    f.write(code)
