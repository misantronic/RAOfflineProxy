from __future__ import annotations

# RetroAchievements console ids, the same table as the Android app's ConsoleNames.
CONSOLE_NAMES = {
    7: "NES", 81: "Famicom Disk System", 3: "SNES", 2: "Nintendo 64", 16: "GameCube",
    19: "Wii", 4: "Game Boy", 6: "Game Boy Color", 5: "Game Boy Advance", 18: "Nintendo DS",
    78: "Nintendo DSi", 24: "Pokemon Mini", 28: "Virtual Boy", 12: "PlayStation",
    21: "PlayStation 2", 41: "PlayStation Portable", 25: "Atari 2600", 51: "Atari 7800",
    17: "Atari Jaguar", 77: "Atari Jaguar CD", 13: "Atari Lynx", 33: "SG-1000",
    11: "Master System", 1: "Genesis/Mega Drive", 9: "Sega CD", 10: "32X", 39: "Saturn",
    40: "Dreamcast", 15: "Game Gear", 47: "PC-8000/8800", 8: "PC Engine/TurboGrafx-16",
    76: "PC Engine CD/TurboGrafx-CD", 49: "PC-FX", 56: "Neo Geo CD", 14: "Neo Geo Pocket",
    43: "3DO Interactive Multiplayer", 37: "Amstrad CPC", 38: "Apple II", 27: "Arcade",
    73: "Arcadia 2001", 71: "Arduboy", 44: "ColecoVision", 75: "Elektor TV Games Computer",
    57: "Fairchild Channel F", 45: "Intellivision", 74: "Interton VC 4000",
    23: "Magnavox Odyssey 2", 69: "Mega Duck", 29: "MSX", 102: "Standalone", 80: "Uzebox",
    46: "Vectrex", 72: "WASM-4", 63: "Watara Supervision", 53: "WonderSwan",
}
UNKNOWN_CONSOLE = "Unknown"


def console_name(console_id: int) -> str:
    return CONSOLE_NAMES.get(console_id, UNKNOWN_CONSOLE)
