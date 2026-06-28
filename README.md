[![CurseForge downloads](https://cf.way2muchnoise.eu/watermedia.svg?badge_style=for_the_badge)](https://www.curseforge.com/minecraft/mc-mods/watermedia)
[![CurseForge](https://img.shields.io/curseforge/v/1029438?style=for-the-badge&label=curseforge&labelColor=%232d2d2d&color=%23e04e14&link=https%3A%2F%2Fwww.curseforge.com%2Fminecraft%2Fmc-mods%2Fwatermedia%2Ffiles)](https://www.curseforge.com/minecraft/mc-mods/watermedia/files)
[![Minecraft versions supported](https://cf.way2muchnoise.eu/versions/Supports_watermedia-binaries_all.svg?badge_style=for_the_badge)](https://www.curseforge.com/minecraft/mc-mods/watermedia/files)
[![JitPack](https://img.shields.io/jitpack/version/com.github.WaterMediaTeam/binaries?style=for-the-badge&label=JITPACK&color=34495e&link=https%3A%2F%2Fjitpack.io%2F%23SrRapero720%2Fwatermedia)](https://jitpack.io/#SrRapero720/watermedia)
[![Build status](https://img.shields.io/github/actions/workflow/status/WaterMediaTeam/binaries/gradle.yml?style=for-the-badge)](https://github.com/WaterMediaTeam/binaries/actions/workflows/gradle.yml)

# WATERMeDIA: BINARIES
Dependency for WATERMeDIA: Multimedia API, a powerful multimedia library for Java applications.
Provides the FFMPEG binaries required for multimedia decoding.

# BINARIES BUILD
~~Binaries are built using GitHub Actions CI.~~ Binaries are pre-built by JavaCPP project
and mirrored here for easier access. It's on the board migrate to build on Actions.

# SUPPORTED PLATFORMS
| Platform | Architecture | Status |
|----------|--------------|:------:|
| Windows  | x86_64       |   ✅    |
| Windows  | aarch64      |   ✅    |
| Linux    | x86_64       |   ✅    |
| Linux    | aarch64      |   ✅    |
| macOS    | x86_64       |   ✅    |
| macOS    | aarch64      |   ✅    |
| Android  | aarch64      |   ⛔    |
| Android  | x86_64       |   ⛔    |

# OTHER SHARED LIBRARIES
- ISPCTextureCompressor (Samsung fork)

# LICENCES
WATERMeDIA: Binaries is licensed under the PolyForm Strict License 1.0.0 (see [`LICENSE.md`](LICENSE.md)).

It ships third-party native binaries and libraries. Their full, verbatim license texts are bundled
under `src/main/resources/META-INF/licenses/` (shipped in the jar as `META-INF/licenses/`), grouped
by license:

- **GPL-3.0** — FFmpeg (native, 8.0.1 "-gpl"; statically links additional GPL/LGPL codec libraries such as x264, x265 and xvid) and libatomic (macOS native, with the GCC Runtime Library Exception 3.1)
- **Apache-2.0** — JavaCPP JNI glue (native)
- **MIT / X11** — libva, libva-drm and libdrm (Linux native); rustypipe-botguard (downloaded binary)
- **0BSD** — XZ for Java (shaded)
- **Unlicense** — yt-dlp (downloaded binary)

**Why is this PolyForm Strict if it ships FFMPEG (GPL)?** Because the GPL itself explicitly allows
*mere aggregation*: bundling separate, independent works on the same distribution medium does not
extend the GPL to them. FFMPEG ships here as such an aggregate, so its copyleft governs only the
bundled GPL binaries and never relicenses WaterMedia's or this module's own code.

To reinforce this:

- **Indirect dependency (scope change)** — the chain is `WaterMedia → JavaCPP (Java API) ──scope change──▶ JNI glue → FFMPEG`. WaterMedia only talks to JavaCPP's Apache-2.0 Java API and never depends on or links FFMPEG directly; the GPL natives and their JNI glue live here, in a separate, **optional**, runtime-scoped jar.
- **Distribution, not dependency** — the GPL is triggered by *distributing* the GPL work, not by depending on it.
- **Replaceable binaries** — anyone may compile and supply their own FFMPEG build instead of using this jar, so nothing is bound to a particular GPL binary.