[![CurseForge downloads](https://cf.way2muchnoise.eu/watermedia-binaries.svg?badge_style=for_the_badge)](https://www.curseforge.com/minecraft/mc-mods/watermedia-binaries)
[![CurseForge](https://img.shields.io/curseforge/v/1029438?style=for-the-badge&label=curseforge&labelColor=%232d2d2d&color=%23e04e14&link=https%3A%2F%2Fwww.curseforge.com%2Fminecraft%2Fmc-mods%2Fwatermedia-binaries%2Ffiles)](https://www.curseforge.com/minecraft/mc-mods/watermedia-binaries/files)
[![Modrinth](https://img.shields.io/modrinth/v/4997XcoK?style=for-the-badge&label=modrinth&labelColor=%232d2d2d&color=%2300af5c)](https://modrinth.com/mod/watermedia-binaries)

## 🦆 WATERMeDIA: Binaries
Companion library for [**WATERMeDIA**](https://www.curseforge.com/minecraft/mc-mods/watermedia) shipping
the pre-built FFMPEG natives, their JNI glue and a few extra shared libraries. With this jar you won't
need to compile or install FFMPEG or any other native application — plug and play as you deserve.

Install it next to WaterMedia; on first launch WaterMedia extracts the natives for your platform and
loads them automatically. On its own this jar stays dormant: it never touches the game, so it can sit
in a modpack even before WaterMedia itself is added.

## 📦 Supported platforms
| Platform | Architecture | Status |
|----------|--------------|:------:|
| Windows  | x86_64       |   ✅    |
| Windows  | aarch64      |   ⛔    |
| Linux    | x86_64       |   ✅    |
| Linux    | aarch64      |   ✅    |
| macOS    | x86_64       |   ✅    |
| macOS    | aarch64      |   ✅    |
| Android  | aarch64      |   ⛔    |
| Android  | x86_64       |   ⛔    |

## 🧰 What is inside
- **FFMPEG 8.x natives** (GPL build) — video and audio decoding for every format WaterMedia plays
- **JavaCPP JNI glue** — the bridge between the Java API and the natives
- **ISPCTextureCompressor** (Samsung fork) — GPU texture compression support

## ⚖️ License
The module's own code is under **PolyForm Strict License 1.0.0**. The bundled third-party natives keep
their own licenses — full, verbatim texts ship inside the jar under `META-INF/licenses/`:

- **GPL-3.0** — FFMPEG (statically linking GPL/LGPL codec libraries such as x264, x265 and xvid) and libatomic (macOS)
- **Apache-2.0** — JavaCPP JNI glue
- **MIT / X11** — libva, libva-drm and libdrm (Linux)
- **0BSD** — XZ for Java (shaded)

Bundling works as *mere aggregation*: the GPL governs only the bundled binaries and never relicenses
WaterMedia's or this module's own code. See the
[WaterMedia README](https://github.com/WaterMediaTeam/watermedia#%EF%B8%8F-license) for the full rationale.
