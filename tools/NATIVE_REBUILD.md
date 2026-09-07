# Native rebuilds

The Maven `8.1.2-1.5.14` GPL classifiers contain statically linked libxml2 2.9.12 and OpenSSL 3.5.7.
`RebuildFFmpeg.ps1` retains the FFmpeg/JavaCPP coordinate and prepares the tagged JavaCPP recipe with
libxml2 2.15.4 and OpenSSL 3.5.8. The source commit, source archive hashes, x264 revision and native
dependency versions are pinned in `gradle.properties`.
The checksum-pinned TLS patch enables certificate verification by default, verifies IP identities,
and preserves trust options through HTTP, HLS and DASH, including manifests read from local files.
The separate `ffmpeg-security.patch` backports the reviewed upstream fixes listed below while retaining
FFmpeg 8.1.2 and the original JavaCPP API. Its independent SHA-256 is mandatory in build and repack records.

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
VDPAU remains disabled as in the original Linux classifiers, keeping their existing system-library contract.

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
The build also checks that every reviewed TLS hunk is present in the actual FFmpeg source tree.
The same reverse-application check covers the security patch. Source checks complement the runtime
probes; they do not claim that every vulnerability has a dedicated exploit regression test.
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

## Reviewed FFmpeg security backports

The following 29 fixes were absent from the selected source snapshot. The consolidated patch contains
30 upstream commits; CVE-2026-66036 includes its required prerequisite. Library and runtime evidence
must identify this patch before these fixes can be claimed for a distributed binary.

| CVE | Upstream fixes |
| --- | --- |
| CVE-2026-58049 | [11ff18a6c801](https://github.com/FFmpeg/FFmpeg/commit/11ff18a6c80187405fc492f9bb07ba9f2f663f76) |
| CVE-2026-64830 | [dbd495f066a8](https://github.com/FFmpeg/FFmpeg/commit/dbd495f066a85ba96b17433f4306582aa37c3951) |
| CVE-2026-64831 | [92737390dc13](https://github.com/FFmpeg/FFmpeg/commit/92737390dc133daadce47dd7d2ec8ef3d9ebcbed) |
| CVE-2026-64832 | [4c6217477fc6](https://github.com/FFmpeg/FFmpeg/commit/4c6217477fc64305055b37d9d1d0d76d30e37f97) |
| CVE-2026-64833 | [6f80e2765492](https://github.com/FFmpeg/FFmpeg/commit/6f80e2765492700622596af720534cef33dd31b4) |
| CVE-2026-64834 | [11d5f475be95](https://github.com/FFmpeg/FFmpeg/commit/11d5f475be95d22d5f0692220cc772b116abc632) |
| CVE-2026-64835 | [1836ef968469](https://github.com/FFmpeg/FFmpeg/commit/1836ef96846937a6cc2443698a693104f5c0b21e) |
| CVE-2026-65703 | [fd3ee52fab34](https://github.com/FFmpeg/FFmpeg/commit/fd3ee52fab34d98a95b787d0b5ff45685766200c) |
| CVE-2026-65704 | [de771bd52774](https://github.com/FFmpeg/FFmpeg/commit/de771bd52774a52d45b0e2c82e56995a1ef40df7) |
| CVE-2026-65705 | [24c322fdb232](https://github.com/FFmpeg/FFmpeg/commit/24c322fdb232d0a3f3790d544dcb64e5c2138e79) |
| CVE-2026-65706 | [a7e38b617b32](https://github.com/FFmpeg/FFmpeg/commit/a7e38b617b32f996beaa371bbf04b39907d7a527) |
| CVE-2026-66036 | [f0f634b6585f](https://github.com/FFmpeg/FFmpeg/commit/f0f634b6585fdc7bbb43ab3ae461499bfca9ad2e), [5d7112c60e6f](https://github.com/FFmpeg/FFmpeg/commit/5d7112c60e6f0f0742ce47d448e6da0718a70f4c) |
| CVE-2026-66037 | [86708357d126](https://github.com/FFmpeg/FFmpeg/commit/86708357d126af84c16f80d9c57335d1e8c845c5) |
| CVE-2026-66038 | [e7cbfd1c507b](https://github.com/FFmpeg/FFmpeg/commit/e7cbfd1c507b57a806a5825b87d609963e862c8c) |
| CVE-2026-66039 | [aafb5c655edc](https://github.com/FFmpeg/FFmpeg/commit/aafb5c655edc76a753275c383ebb139feb032718) |
| CVE-2026-66040 | [b506fafec9a1](https://github.com/FFmpeg/FFmpeg/commit/b506fafec9a19fcbc2be5271875fd4a63d6615bc) |
| CVE-2026-66041 | [4da9812e2589](https://github.com/FFmpeg/FFmpeg/commit/4da9812e25894fb51d62a8875cfa8eb39b5e20f5) |
| CVE-2026-70628 | [93f2a525ec6c](https://github.com/FFmpeg/FFmpeg/commit/93f2a525ec6c7b467bae68322720d10188fc6e30) |
| CVE-2026-70629 | [cd1f545cf27b](https://github.com/FFmpeg/FFmpeg/commit/cd1f545cf27ba08f6f5b31b1e92665d7874d4fd7) |
| CVE-2026-70630 | [705890061467](https://github.com/FFmpeg/FFmpeg/commit/705890061467ad550ecc1dad5eea07f28ccfb43e) |
| CVE-2026-70631 | [2f234ea34c81](https://github.com/FFmpeg/FFmpeg/commit/2f234ea34c81288e3840fca632dd16481d8de39f) |
| CVE-2026-70632 | [db05df9d135f](https://github.com/FFmpeg/FFmpeg/commit/db05df9d135fb56a4babb836d5e9f5c1d984e087) |
| CVE-2026-75141 | [acf5d7cdc1f9](https://github.com/FFmpeg/FFmpeg/commit/acf5d7cdc1f9ae8752c23e1ea8d7f355ed780781) |
| CVE-2026-75142 | [9d786e4b5e9b](https://github.com/FFmpeg/FFmpeg/commit/9d786e4b5e9b8482651928574de33772aeee7be1) |
| CVE-2026-75143 | [1c10bcc2e172](https://github.com/FFmpeg/FFmpeg/commit/1c10bcc2e17255dacb717a25ab3db142ce390602) |
| CVE-2026-75144 | [1cdeb3c4e7f1](https://github.com/FFmpeg/FFmpeg/commit/1cdeb3c4e7f1f8566d846b9b451e01c376398818) |
| CVE-2026-75145 | [b4c199c5906f](https://github.com/FFmpeg/FFmpeg/commit/b4c199c5906ff53368926c2a5839881f41957e7f) |
| CVE-2026-75146 | [65b0dab903e5](https://github.com/FFmpeg/FFmpeg/commit/65b0dab903e5975e036b30ecc58f5935d4f151e0) |
| CVE-2026-75147 | [983dae9c19f4](https://github.com/FFmpeg/FFmpeg/commit/983dae9c19f46c87d597598c0fd2f2fcee0ad2f8) |

These three reviewed fixes already exist in the selected source and are not applied again:

| CVE | Existing upstream fix |
| --- | --- |
| CVE-2026-6385 | [1bde76da8901](https://github.com/FFmpeg/FFmpeg/commit/1bde76da890127608744c3b17669a99d2adce54a) |
| CVE-2026-13858 | [256d93413f26](https://github.com/FFmpeg/FFmpeg/commit/256d93413f260e1524c2ab994dc72c4edaa0d04c) |
| CVE-2026-38348 | [fc6dcb4aaf2a](https://github.com/FFmpeg/FFmpeg/commit/fc6dcb4aaf2aa2b832b0bc92297cc91b07f2ea4d) |
