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
- **FFMPEG 8.1.2 natives** (GPL build, JavaCPP 1.5.14) — video and audio decoding for every format WaterMedia plays
- **JavaCPP JNI glue** — the bridge between the Java API and the natives
- **yt-dlp and BotGuard provisioning** — verified executable downloads on first use

## ⚖️ License
The module's own code is under **PolyForm Strict License 1.0.0**. The bundled third-party natives keep
their own licenses — full, verbatim texts ship inside the jar under `META-INF/licenses/`:

- **GPL-3.0** — FFMPEG (statically linking GPL/LGPL codec libraries such as x264 and x265) and libatomic (macOS)
- **Apache-2.0** — JavaCPP JNI glue
- **MIT / X11** — libva, libva-drm and libdrm (Linux)
- **MIT / BSD-3-Clause** — winpthreads (Windows)
- **0BSD** — XZ for Java (shaded)

Bundling works as *mere aggregation*: the GPL governs only the bundled binaries and never relicenses
WaterMedia's or this module's own code. See the
[WaterMedia README](https://github.com/WaterMediaTeam/watermedia#%EF%B8%8F-license) for the full rationale.

## Rebuilding and repacking FFmpeg

The distributed archives come from complete JavaCPP source builds so their statically linked
dependencies and JNI glue can be verified together. Follow [`tools/NATIVE_REBUILD.md`](tools/NATIVE_REBUILD.md)
to rebuild each supported platform on its matching operating system. Candidate JARs and their verified
provenance records are collected under `build/rebuilt/<platform>`.

After all five candidates pass, run `java tools/RepackFFmpeg.java . build/rebuilt` with JDK 17 or newer.
The tool reads the pinned versions, source hashes and compression level from `gradle.properties`, and
checks the parent WaterMedia FFmpeg version when used as its submodule. It rejects missing or mismatched
candidate records and refuses to replace a rebuilt distribution with the original Maven classifiers.

Every shared library and JNI dependency is copied into the flat runtime layout; CLI programs are
excluded. ZIP compression uses DEFLATE level 9, with fixed entry timestamps and ordering. Every output
is checked against its candidate with CRC and SHA-256 before any resource is replaced.
`tools/ffmpeg-manifest.properties` records source provenance, checksums, sizes and native file hashes.

Running the `FFmpeg Rebuild` workflow for `all` platforms rebuilds the five candidates, downloads each
successful matrix artifact into its exact platform directory, repacks the complete set and uploads the
verified `ffmpeg-gpl` artifact. A single-platform run uploads only that candidate for diagnosis or retry.

## Host lifecycle and downloads

This module builds independently of WaterMedia. A host calls `WaterMediaBinaries.resolve(cacheRoot)`,
then `provision(progress)` when FFmpeg is needed. Configuration and lifecycle decisions belong to
the host. Call `release()` after consumers stop to clear bindings without deleting loaded native files.
Each FFmpeg startup verifies every installed library against the bundled SHA-256 manifest. Repairs
and upgrades create a separate installation and publish it only after complete verification.

yt-dlp requires its published `SHA2-256SUMS`; missing or malformed entries stop installation.
BotGuard is pinned to the reviewed v0.1.2 archives in `META-INF/botguard-pins.properties` because
upstream publishes no checksum or signature. Those WaterMedia pins record archives originally
downloaded over Codeberg HTTPS; they do not claim an upstream signature. Updating BotGuard requires
reviewing all replacement URLs and hashes. Both installers record provenance and verify cached files.
Explicit `watermedia.ytdlp.binary` and `watermedia.botguard.binary` paths are trusted host overrides.

Old native installations remain available while DLLs may be loaded. Cache cleanup belongs to the
host after all processes using that cache stop.
