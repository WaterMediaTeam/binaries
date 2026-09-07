# Native rebuilds

The Maven `8.1.2-1.5.14` GPL classifiers contain statically linked libxml2 2.9.12 and OpenSSL 3.5.7.
`RebuildFFmpeg.ps1` retains the FFmpeg/JavaCPP coordinate and prepares the tagged JavaCPP recipe with
libxml2 2.15.4 and OpenSSL 3.5.8. The source commit, source archive hashes, x264 revision and native
dependency versions are pinned in `gradle.properties`.
The checksum-pinned TLS patch enables certificate verification by default, verifies IP identities,
and preserves trust options through HTTP, HLS and DASH, including manifests read from local files.

```powershell
./tools/SetupJava.ps1 -Platform windows-x86_64
./tools/SetupNativeTools.ps1 -Platform windows-x86_64
./tools/RebuildFFmpeg.ps1 -Platform windows-x86_64
```

The other platforms are `linux-x86_64`, `linux-arm64`, `macosx-x86_64` and `macosx-arm64`.
Use each platform's own OS and CPU. Windows requires an initialized MSYS2 MINGW64 environment with
its `mingw64/bin` and `usr/bin` directories on PATH, plus `MSYSTEM=MINGW64` and
`MSYS2_PATH_TYPE=inherit`. Linux and macOS prerequisites are listed in the `FFmpeg Rebuild` workflow.
The exact Microsoft JDK is downloaded using a pinned SHA-256 and its installed version is checked.
Maven and CMake are downloaded as verified archives into this project's `build/native-tools`.
The build uses Meson and its runtime as upstream build dependencies; no system-wide tool installation
is performed by these scripts.

`-PrepareOnly` downloads and verifies source archives and patches the pristine recipe without
running a native compiler. `-VerifyOnly` validates an already built classifier without recompiling.
The build preserves the upstream codec and hardware configuration, and adds no DASH format restriction.

Maven runs the complete JavaCPP preset in three phases: compile and install FFmpeg with its static
codec dependencies, parse its headers, then compile all seven JNI libraries with `copyLibs` and
`copyResources`. A standalone FFmpeg CLI build cannot replace these classifier JARs. Linux preparation
also provides pinned Vulkan headers and the ARM64 Raspberry Pi userland expected by the original preset.
On macOS, the GCC library directory is passed directly to Maven so JavaCPP packages `libatomic`.

Before a candidate is exported, the script generates a local H.264 DASH fixture and launches a fresh
Java process using the original, checksum-pinned Maven Java wrappers. It must load all seven JNI
components, find x264/x265, identify the expected embedded libxml2 and OpenSSL versions, parse the DASH
fixture and reject a recursive-entity manifest. This process receives only the packaged native
directory, JDK and operating-system paths; compiler directories and inherited Java options are removed.
PE, ELF or Mach-O imports must resolve to packaged libraries or the explicit operating-system allowlist.
macOS library references are rewritten to `@loader_path`, signed again and saved into the candidate JAR.
These checks are regression probes, not proof of exhaustive vulnerability coverage.
A second fresh Java process runs `VerifyTLS.java` against local HTTPS fixtures. Trusted DNS/IP peers
and nested HLS/DASH reads must work; untrusted certificates and incorrect endpoint identities must
be rejected before any HTTP request reaches the rejected peer. Candidates are exported only after
both probes pass, and their records include the exact TLS patch hash.
Local manifest fixtures explicitly allow network protocols for these checks; production protocol
restrictions remain unchanged. Proxy settings are removed from the loopback verification processes.

The Linux runtime contract matches the original classifiers: glibc and C/C++ runtimes, udev,
PulseAudio, XCB, ALSA, VA-API and DRM may come from the operating system. Some are already packaged by
the preset, depending on architecture. These device and display libraries are distinct from the
statically linked codec, XML and TLS libraries. A minimal Linux container still needs those system
packages; dependencies outside the declared set are rejected.

Candidate JARs and their provenance are placed under `build/rebuilt/<platform>`. The workflow uploads
only successfully verified candidates and never creates branches, commits, releases or publications.
Its platform selector can retry one failed target while the other builds continue. Failed jobs may
upload separate `unverified-ffmpeg-*` diagnostics containing intermediate JARs, the CLI and build logs.
These lack the successful verification record and must never replace the distributed libraries.
All five candidates must pass before replacing the distributed native set. Updating a recipe does
not change the currently bundled ZIPs by itself; the source manifest must match any replaced archives.

Collect all five artifact directories under `build/rebuilt/<platform>` and run:

```powershell
java tools/RepackFFmpeg.java . build/rebuilt
```

The repacker requires matching source/version/verification records and candidate SHA-256 hashes,
then verifies every native file after DEFLATE level 9 compression. It records rebuilt provenance in
the shared runtime manifest. Once rebuilt natives are installed, running the repacker without a
candidate directory fails instead of silently replacing them with the original Maven binaries.

Sources: [JavaCPP Presets 1.5.14](https://github.com/bytedeco/javacpp-presets/tree/1.5.14),
[libxml2 2.15.4 release notes](https://download.gnome.org/sources/libxml2/2.15/libxml2-2.15.4.news),
[OpenSSL August 2026 advisory](https://mirror.openssl-library.org/news/secadv/20260825.txt).
