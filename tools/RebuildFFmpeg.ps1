param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('windows-x86_64', 'linux-x86_64', 'linux-arm64', 'macosx-x86_64', 'macosx-arm64')]
    [string] $Platform,
    [switch] $PrepareOnly,
    [switch] $VerifyOnly
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$properties = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $root 'gradle.properties')) {
    if ($line -match '^([^#!\s][^=]*)=(.*)$') { $properties[$Matches[1]] = $Matches[2] }
}
$revision = $properties.ffmpeg_source_ref
$xmlVersion = $properties.libxml2_version
$sslVersion = $properties.openssl_version
$x264 = $properties.ffmpeg_x264_ref
$tlsPatch = Join-Path $PSScriptRoot 'ffmpeg-tls.patch'
if ($properties.ffmpeg_tls_patch_sha256 -notmatch '^[a-f0-9]{64}$' -or (Get-FileHash -LiteralPath $tlsPatch -Algorithm SHA256).Hash -ine $properties.ffmpeg_tls_patch_sha256) { throw 'Native TLS patch SHA-256 mismatch' }
$securityPatch = Join-Path $PSScriptRoot 'ffmpeg-security.patch'
if ($properties.ffmpeg_security_patch_sha256 -notmatch '^[a-f0-9]{64}$' -or (Get-FileHash -LiteralPath $securityPatch -Algorithm SHA256).Hash -ine $properties.ffmpeg_security_patch_sha256) { throw 'Native security patch SHA-256 mismatch' }
if ($revision -notmatch '^[a-f0-9]{40}$' -or $x264 -notmatch '^[a-f0-9]{40}$' -or $xmlVersion -notmatch '^\d+\.\d+\.\d+$' -or $sslVersion -notmatch '^\d+\.\d+\.\d+$') {
    throw 'Native source revisions and libxml2 version must be pinned in gradle.properties'
}
$work = Join-Path $root 'build/native-rebuild'
[IO.Directory]::CreateDirectory($work) | Out-Null
$sourceArchive = Join-Path $work "javacpp-presets-$revision.zip"
$sourceUrl = "https://github.com/bytedeco/javacpp-presets/archive/$revision.zip"
$xmlArchive = Join-Path $work "libxml2-$xmlVersion.tar.xz"
$xmlSeries = ($xmlVersion -split '\.')[0..1] -join '.'
$xmlUrl = "https://download.gnome.org/sources/libxml2/$xmlSeries/libxml2-$xmlVersion.tar.xz"
$sslArchive = Join-Path $work "openssl-$sslVersion.tar.gz"
$sslUrl = "https://github.com/openssl/openssl/releases/download/openssl-$sslVersion/openssl-$sslVersion.tar.gz"
foreach ($download in @(
    @{ Url = $sourceUrl; Path = $sourceArchive; Hash = $properties.ffmpeg_source_sha256 },
    @{ Url = $xmlUrl; Path = $xmlArchive; Hash = $properties.libxml2_sha256 },
    @{ Url = $sslUrl; Path = $sslArchive; Hash = $properties.openssl_sha256 }
)) {
    if (!(Test-Path -LiteralPath $download.Path)) { Invoke-WebRequest -Uri $download.Url -OutFile $download.Path }
    if ((Get-FileHash -LiteralPath $download.Path -Algorithm SHA256).Hash -ine $download.Hash) {
        throw "Source archive SHA-256 mismatch: $($download.Url)"
    }
}
# SHORT SOURCE PATHS KEEP LEGACY C/C++ TOOLS BELOW WINDOWS MAX_PATH, INCLUDING RELATIVE INCLUDES.
$source = Join-Path $work ('src/' + $revision.Substring(0, 12))
$sourceMarker = Join-Path $source '.source-sha256'
if (!(Test-Path -LiteralPath $source)) {
    $unpack = Join-Path $work ([Guid]::NewGuid().ToString('N'))
    Expand-Archive -LiteralPath $sourceArchive -DestinationPath $unpack
    $extracted = [IO.Path]::GetFullPath((Join-Path $unpack "javacpp-presets-$revision"))
    $destination = [IO.Path]::GetFullPath($source)
    $workPrefix = [IO.Path]::GetFullPath($work) + [IO.Path]::DirectorySeparatorChar
    if (!$extracted.StartsWith($workPrefix, [StringComparison]::OrdinalIgnoreCase) -or !$destination.StartsWith($workPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Native source extraction escaped its build directory' }
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
    Move-Item -LiteralPath $extracted -Destination $destination
    Remove-Item -LiteralPath $unpack
    [IO.File]::WriteAllText($sourceMarker, $properties.ffmpeg_source_sha256, [Text.UTF8Encoding]::new($false))
}
if (!(Test-Path -LiteralPath $sourceMarker) -or (Get-Content -LiteralPath $sourceMarker -Raw) -cne $properties.ffmpeg_source_sha256) { throw 'Native source directory does not match the pinned source archive' }

# PATCH THE VERIFIED PRISTINE RECIPE ON EVERY RUN, PRESERVING ITS OTHER CODECS AND PLATFORM FLAGS.
$zip = [IO.Compression.ZipFile]::OpenRead($sourceArchive)
try {
    $entry = $zip.GetEntry("javacpp-presets-$revision/ffmpeg/cppbuild.sh")
    if ($null -eq $entry) { throw 'Verified source archive lacks the FFmpeg recipe' }
    $reader = [IO.StreamReader]::new($entry.Open())
    try { $recipe = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $reader = [IO.StreamReader]::new($zip.GetEntry("javacpp-presets-$revision/ffmpeg/pom.xml").Open())
    try { $pom = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $reader = [IO.StreamReader]::new($zip.GetEntry("javacpp-presets-$revision/pom.xml").Open())
    try { $parentPom = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally { $zip.Dispose() }
$changes = @(
    @('XML2=libxml2-2.9.12', "XML2=libxml2-$xmlVersion"),
    @('OPENSSL=openssl-3.5.7', "OPENSSL=openssl-$sslVersion"),
    @('X264=x264-stable', "X264=x264-$x264"),
    @('download https://code.videolan.org/videolan/x264/-/archive/stable/$X264.tar.gz $X264.tar.gz', "download https://code.videolan.org/videolan/x264/-/archive/$x264/`$X264.tar.gz `$X264.tar.gz"),
    @('download http://xmlsoft.org/sources/$XML2.tar.gz $XML2.tar.gz', "download https://download.gnome.org/sources/libxml2/$xmlSeries/`$XML2.tar.xz `$XML2.tar.xz"),
    @('tar --totals -xzf ../$XML2.tar.gz', 'tar --totals -xJf ../$XML2.tar.xz'),
    @('--without-iconv --without-python --without-lzma --with-pic', '--without-iconv --with-pic'),
    @('echo "pkg-config=', 'echo "pkgconfig='),
    @('--pkg-config-path=/usr/bin/pkg-config', '--pkg-config-path=$INSTALL_PATH/lib/pkgconfig'),
    @('echo "[binaries]" >>', 'echo "[binaries]" >'),
    @('--disable-xlib"', '--disable-xlib --disable-vdpau"'),
    @('-lWs2_32 -lcrypt32 -lpthread', '-lWs2_32 -lcrypt32 -lbcrypt -lpthread'),
    @('patch -Np1 -d ffmpeg-$FFMPEG_VERSION < ../../ffmpeg.patch', ('patch -Np1 -d ffmpeg-$FFMPEG_VERSION < ../../ffmpeg.patch' + "`n" + 'patch -Np1 -d ffmpeg-$FFMPEG_VERSION < ../../ffmpeg-tls.patch')),
    @('patch -Np1 -d ffmpeg-$FFMPEG_VERSION < ../../ffmpeg-tls.patch', ('patch -Np1 -d ffmpeg-$FFMPEG_VERSION < ../../ffmpeg-tls.patch' + "`n" + 'patch -Np1 -d ffmpeg-$FFMPEG_VERSION < ../../ffmpeg-security.patch'))
)
foreach ($change in $changes) {
    if ($change.Count -ne 2) { throw 'Each native recipe replacement must contain exactly two strings' }
    if (!$recipe.Contains($change[0])) { throw "Expected upstream recipe fragment is missing: $($change[0])" }
    $recipe = $recipe.Replace($change[0], $change[1])
}
if (!$recipe.Contains("FFMPEG_VERSION=$($properties.ffmpeg_version.Split('-')[0])")) { throw 'Source FFmpeg version disagrees with the Java wrappers' }
if ([regex]::Matches($recipe, [regex]::Escape('patch -Np1 -d ffmpeg-$FFMPEG_VERSION < ../../ffmpeg-tls.patch')).Count -ne 1) { throw 'The native recipe must apply the TLS patch exactly once' }
if ([regex]::Matches($recipe, [regex]::Escape('patch -Np1 -d ffmpeg-$FFMPEG_VERSION < ../../ffmpeg-security.patch')).Count -ne 1) { throw 'The native recipe must apply the security patch exactly once' }
Copy-Item -LiteralPath $tlsPatch -Destination (Join-Path $source 'ffmpeg/ffmpeg-tls.patch') -Force
Copy-Item -LiteralPath $securityPatch -Destination (Join-Path $source 'ffmpeg/ffmpeg-security.patch') -Force
[IO.File]::WriteAllText((Join-Path $source 'ffmpeg/cppbuild.sh'), $recipe, [Text.UTF8Encoding]::new($false))
$upstreamVersion = '<version>' + $properties.ffmpeg_version.Split('-')[0] + '-${project.parent.version}</version>'
if (!$pom.Contains($upstreamVersion)) { throw 'Unexpected FFmpeg Maven version expression' }
$pom = $pom.Replace($upstreamVersion, "<version>$($properties.ffmpeg_version)</version>")
[IO.File]::WriteAllText((Join-Path $source 'ffmpeg/pom.xml'), $pom, [Text.UTF8Encoding]::new($false))
$cache = Join-Path $source 'downloads'
[IO.Directory]::CreateDirectory($cache) | Out-Null
Copy-Item -LiteralPath $xmlArchive -Destination (Join-Path $cache "libxml2-$xmlVersion.tar.xz") -Force
Copy-Item -LiteralPath $sslArchive -Destination (Join-Path $cache "openssl-$sslVersion.tar.gz") -Force
Write-Output "Prepared $Platform sources: $source"
Write-Output "Verified libxml2 $xmlVersion SHA-256: $($properties.libxml2_sha256)"
Write-Output "Verified OpenSSL $sslVersion SHA-256: $($properties.openssl_sha256)"
Write-Output "Verified TLS patch SHA-256: $($properties.ffmpeg_tls_patch_sha256)"
Write-Output "Verified security patch SHA-256: $($properties.ffmpeg_security_patch_sha256)"
if ($PrepareOnly) { return }

if (!$VerifyOnly) {
    $nativeBuild = [IO.Path]::GetFullPath((Join-Path $source "ffmpeg/cppbuild/$Platform-gpl"))
    $ffmpegSource = [IO.Path]::GetFullPath((Join-Path $nativeBuild "ffmpeg-$($properties.ffmpeg_version.Split('-')[0])"))
    $nativePrefix = $nativeBuild + [IO.Path]::DirectorySeparatorChar
    if (!$ffmpegSource.StartsWith($nativePrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'FFmpeg source cleanup escaped its build directory' }
    # TAR OVERLAYS DO NOT REMOVE FILES ADDED BY PATCHES; RESET ONLY THE GENERATED FFMPEG SOURCE TREE.
    if (Test-Path -LiteralPath $ffmpegSource) { Remove-Item -LiteralPath $ffmpegSource -Recurse -Force }
    foreach ($tool in @('bash', 'mvn', 'cmake', 'make', 'meson', 'ninja', 'pkg-config', 'git')) {
        if (!(Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Missing native build prerequisite: $tool" }
    }
    # WINDOWS CREATEPROCESS SEARCHES SYSTEM32 BEFORE PATH; USE THE CONFIGURED SHELL'S ABSOLUTE PATH.
    $shells = @(Get-Command bash -All -CommandType Application -ErrorAction Stop)
    if ($Platform.StartsWith('windows-')) {
        $shells = @($shells | Where-Object {
            $directory = [IO.Path]::GetDirectoryName($_.Source)
            (Test-Path -LiteralPath (Join-Path $directory 'msys-2.0.dll')) -and (Test-Path -LiteralPath (Join-Path $directory 'pacman.exe'))
        })
    }
    if ($shells.Count -eq 0) { throw 'Native Windows builds require the MSYS2 usr/bin directory, including bash, pacman and its runtime, on PATH' }
    $nativeBash = $shells[0].Source
    Write-Output "Native build shell: $nativeBash"
    if (!$parentPom.Contains('<program>bash</program>')) { throw 'Unexpected JavaCPP shell configuration' }
    $parentPom = $parentPom.Replace('<program>bash</program>', '<program>' + [Security.SecurityElement]::Escape($nativeBash.Replace('\', '/')) + '</program>')
    [IO.File]::WriteAllText((Join-Path $source 'pom.xml'), $parentPom, [Text.UTF8Encoding]::new($false))
    $env:MAKEJ = [Math]::Max(1, [Environment]::ProcessorCount - 2)
    $env:MAVEN_OPTS = '-Xss2m -Xmx4g'
    $nativeOptions = @()
    if ($Platform -eq 'linux-arm64') {
        if (!$env:USERLAND_PATH -or !(Test-Path -LiteralPath (Join-Path $env:USERLAND_PATH 'build/lib'))) { throw 'Prepared Raspberry Pi userland is required for the ARM64 preset' }
        $nativeOptions += "-Djava.library.path=$($env:USERLAND_PATH)/build/lib"
    } elseif ($Platform.StartsWith('macosx-')) {
        if (!$env:GCC_LIBRARY_PATH -or !(Test-Path -LiteralPath (Join-Path $env:GCC_LIBRARY_PATH 'libatomic.1.dylib'))) { throw 'Prepared GCC libatomic is required for the macOS preset' }
        $nativeOptions += "-Djava.library.path=$env:GCC_LIBRARY_PATH"
    }
    Push-Location $source
    try {
        & mvn -B -f ffmpeg/pom.xml package "-Djavacpp.platform=$Platform" '-Djavacpp.platform.extension=-gpl' '-DskipTests' @nativeOptions
        if ($LASTEXITCODE -ne 0) { throw "FFmpeg native compilation failed for $Platform" }
    } finally { Pop-Location }
}
$ffmpegSource = [IO.Path]::GetFullPath((Join-Path $source "ffmpeg/cppbuild/$Platform-gpl/ffmpeg-$($properties.ffmpeg_version.Split('-')[0])"))
$patchDirectory = [IO.Path]::GetRelativePath($root, $ffmpegSource).Replace('\', '/')
if ($patchDirectory.StartsWith('../', [StringComparison]::Ordinal)) { throw 'Native TLS verification escaped its build directory' }
& git -C $root apply --reverse --check "--directory=$patchDirectory" $tlsPatch
if ($LASTEXITCODE -ne 0) { throw 'Compiled FFmpeg sources do not contain the complete reviewed TLS patch' }
& git -C $root apply --reverse --check "--directory=$patchDirectory" $securityPatch
if ($LASTEXITCODE -ne 0) { throw 'Compiled FFmpeg sources do not contain the complete reviewed security patch' }
$classifier = "ffmpeg-$($properties.ffmpeg_version)-$Platform-gpl.jar"
$jar = Join-Path $source "ffmpeg/target/ffmpeg-$Platform-gpl.jar"
if (!(Test-Path -LiteralPath $jar)) { throw "Native build did not produce its classifier archive: $jar" }
$native = Join-Path $source "ffmpeg/cppbuild/$Platform-gpl"
$suffix = if ($Platform.StartsWith('windows-')) { '.exe' } else { '' }
$program = Join-Path $native "bin/ffmpeg$suffix"
$smoke = Join-Path $work "smoke-$Platform"
[IO.Directory]::CreateDirectory($smoke) | Out-Null
$packaged = Join-Path $smoke ([Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($packaged) | Out-Null
$nativeFiles = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::OrdinalIgnoreCase)
$prefix = "org/bytedeco/ffmpeg/$Platform-gpl/"
$archive = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    foreach ($entry in $archive.Entries) {
        if (!$entry.FullName.StartsWith($prefix, [StringComparison]::Ordinal)) { continue }
        $name = $entry.FullName.Substring($prefix.Length)
        if ($name -notmatch '\.(dll|dylib|so(?:\.\d+)*)$') { continue }
        if ($name -ne [IO.Path]::GetFileName($name) -or $nativeFiles.ContainsKey($name)) { throw "Invalid native archive entry: $name" }
        $destination = Join-Path $packaged $name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $false)
        $nativeFiles.Add($name, $destination)
    }
} finally { $archive.Dispose() }
if ($nativeFiles.Count -lt 14) { throw 'Native archive lacks FFmpeg or JavaCPP JNI libraries' }
$component = '^(lib)?(jni)?(avcodec|avdevice|avfilter|avformat|avutil|swscale|swresample)[.-]'
$support = '^(libwinpthread-1\.dll|libatomic\.1\.dylib|libva(?:-drm)?\.so\.2|libdrm\.so\.2|libasound\.so\.2|libbcm_host\.so|libvchiq_arm\.so|libvcos\.so|(?:lib)?jnijavacpp\.(?:dll|so|dylib))$'
$imports = [Collections.Generic.List[string]]::new()
foreach ($file in $nativeFiles.GetEnumerator()) {
    if ($file.Key -notmatch $component -and $file.Key -notmatch $support) { throw "Unexpected shared dependency; codec dependencies must be static: $($file.Key)" }
    if ($Platform.StartsWith('windows-')) {
        $details = & objdump -p $file.Value
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect native PE imports: $($file.Key)" }
        $dependencies = @($details | Select-String '^\s*DLL Name:\s*(\S+)' | ForEach-Object { $_.Matches[0].Groups[1].Value })
        $system = '^(api-ms-win-.*|ext-ms-win-.*|kernel32|kernelbase|ntdll|msvcrt|ucrtbase|advapi32|user32|gdi32|ole32|oleaut32|shell32|shlwapi|ws2_32|crypt32|bcrypt|bcryptprimitives|secur32|version|winmm|setupapi|d3d11|dxgi|dxva2|mf|mfplat|mfreadwrite|imm32|psapi|dwmapi|comdlg32|comctl32|winspool|wtsapi32|iphlpapi|normaliz|dbghelp|msimg32|avicap32)\.dll$'
    } elseif ($Platform.StartsWith('linux-')) {
        $details = & readelf -d $file.Value
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect native ELF imports: $($file.Key)" }
        $dependencies = @($details | Select-String '\(NEEDED\).*\[(.+)\]' | ForEach-Object { $_.Matches[0].Groups[1].Value })
        $system = '^(ld-linux[^/]*\.so(?:\.\d+)*|lib(c|m|mvec|dl|pthread|rt|resolv|util|gcc_s|stdc\+\+)\.so(?:\.\d+)*|libudev\.so\.1|libpulse\.so\.0|libxcb\.so\.1|libxcb-shm\.so\.0|libasound\.so\.2|libva\.so\.2|libva-drm\.so\.2|libdrm\.so\.2)$'
    } else {
        $details = & otool -L $file.Value
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect native Mach-O imports: $($file.Key)" }
        $identity = & otool -D $file.Value
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect native Mach-O identity: $($file.Key)" }
        $ownName = ($identity | Select-Object -Skip 1 | Select-Object -First 1).Trim()
        $dependencies = @($details | Select-Object -Skip 1 | ForEach-Object { ($_.Trim() -split '\s+', 2)[0] } | Where-Object { $_ -ne $ownName })
        $system = '^(/usr/lib/|/System/Library/)'
    }
    foreach ($dependency in $dependencies) {
        $name = [IO.Path]::GetFileName($dependency)
        if (!$nativeFiles.ContainsKey($name) -and $dependency -match $system) {
            $imports.Add("$($file.Key) -> $dependency [system]")
            continue
        }
        if (!$nativeFiles.ContainsKey($name)) { throw "Native dependency is not packaged: $($file.Key) -> $dependency" }
        $imports.Add("$($file.Key) -> $name [packaged]")
        if ($Platform.StartsWith('macosx-')) {
            & install_name_tool -change $dependency "@loader_path/$name" $file.Value
            if ($LASTEXITCODE -ne 0) { throw "Cannot make native dependency portable: $dependency" }
        }
    }
    if ($Platform.StartsWith('macosx-')) {
        & install_name_tool -id "@loader_path/$($file.Key)" $file.Value
        if ($LASTEXITCODE -ne 0) { throw 'Cannot set portable native install name' }
        & codesign --force --sign - $file.Value
        if ($LASTEXITCODE -ne 0) { throw 'Cannot sign the portable native library' }
    }
}
if ($Platform.StartsWith('macosx-')) {
    # PORTABLE INSTALL NAMES AND THEIR AD-HOC SIGNATURES MUST ALSO BE WRITTEN INTO THE CANDIDATE JAR.
    $archive = [IO.Compression.ZipFile]::Open($jar, [IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($file in $nativeFiles.GetEnumerator()) {
            $archive.GetEntry($prefix + $file.Key).Delete()
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.Value, $prefix + $file.Key, [IO.Compression.CompressionLevel]::SmallestSize) | Out-Null
        }
    } finally { $archive.Dispose() }
}
$env:PATH = $packaged + [IO.Path]::PathSeparator + (Join-Path $native 'bin') + [IO.Path]::PathSeparator + $env:PATH
$env:LD_LIBRARY_PATH = $packaged + ':' + $env:LD_LIBRARY_PATH
$env:DYLD_LIBRARY_PATH = $packaged + ':' + $env:DYLD_LIBRARY_PATH
Push-Location $smoke
try {
    & $program -nostdin -v error -f lavfi -i 'color=c=black:s=32x32:r=1' -t 1 -c:v libx264 -an -f dash -y 'stream.mpd'
    if ($LASTEXITCODE -ne 0) { throw 'Rebuilt FFmpeg cannot produce the DASH regression fixture' }
} finally { Pop-Location }
$javaVersion = $properties.ffmpeg_version.Split('-')[1]
$wrapper = Join-Path $work "ffmpeg-$($properties.ffmpeg_version).jar"
if (!(Test-Path -LiteralPath $wrapper)) {
    Invoke-WebRequest "https://repo.maven.apache.org/maven2/org/bytedeco/ffmpeg/$($properties.ffmpeg_version)/ffmpeg-$($properties.ffmpeg_version).jar" -OutFile $wrapper
}
if ((Get-FileHash -LiteralPath $wrapper -Algorithm SHA256).Hash -ine $properties.ffmpeg_wrapper_sha256) { throw 'Original FFmpeg wrapper SHA-256 mismatch' }
$javaCpp = Join-Path ([Environment]::GetFolderPath('UserProfile')) ".m2/repository/org/bytedeco/javacpp/$javaVersion/javacpp-$javaVersion.jar"
if (!(Test-Path -LiteralPath $wrapper) -or !(Test-Path -LiteralPath $javaCpp)) { throw 'Native smoke-test Java dependencies are missing' }
if ((Get-FileHash -LiteralPath $javaCpp -Algorithm SHA256).Hash -ine $properties.javacpp_sha256) { throw 'JavaCPP runtime SHA-256 mismatch' }
$classpath = @($wrapper, $javaCpp) -join [IO.Path]::PathSeparator
$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = Join-Path $env:JAVA_HOME "bin/java$suffix"
$start.WorkingDirectory = $smoke
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$arguments = @('--enable-native-access=ALL-UNNAMED', "-Dorg.bytedeco.javacpp.platform.preloadpath=$packaged", '-Dorg.bytedeco.javacpp.pathsFirst=true', "-Dorg.bytedeco.javacpp.cachedir=$packaged/cache", "-Djava.library.path=$packaged", '-cp', $classpath)
$start.Environment['PATH'] = if ($Platform.StartsWith('windows-')) { "$env:JAVA_HOME/bin;$env:SystemRoot/System32;$env:SystemRoot" } else { "$env:JAVA_HOME/bin:/usr/bin:/bin" }
$start.Environment['LD_LIBRARY_PATH'] = $packaged
$start.Environment['DYLD_LIBRARY_PATH'] = $packaged
$start.Environment['DYLD_FALLBACK_LIBRARY_PATH'] = $packaged
foreach ($name in @('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH')) { [void]$start.Environment.Remove($name) }
foreach ($name in @('HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'http_proxy', 'https_proxy', 'all_proxy')) { [void]$start.Environment.Remove($name) }
$start.Environment['NO_PROXY'] = '*'
$start.Environment['no_proxy'] = '*'
foreach ($probe in @(
    @{ File = 'VerifyFFmpeg.java'; Arguments = @($properties.ffmpeg_version.Split('-')[0], $xmlVersion, $sslVersion, $jar, (Join-Path $smoke 'stream.mpd')) },
    @{ File = 'VerifyTLS.java'; Arguments = @((Join-Path $smoke 'stream.mpd')) }
)) {
    $start.ArgumentList.Clear()
    foreach ($argument in $arguments + (Join-Path $PSScriptRoot $probe.File) + $probe.Arguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit(180000)) {
            $process.Kill($true)
            throw "Native verification deadline exceeded: $($probe.File)"
        }
        Write-Output $stdout.GetAwaiter().GetResult()
        Write-Output $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "Native verification failed in the clean environment: $($probe.File)" }
    } finally {
        $process.Dispose()
    }
}
$output = Join-Path $root "build/rebuilt/$Platform"
[IO.Directory]::CreateDirectory($output) | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $output $classifier) -Force
$lines = @(
    "version=$($properties.ffmpeg_version)",
    "platform=$Platform",
    "source.ref=$revision",
    "source.sha256=$($properties.ffmpeg_source_sha256)",
    "libxml2.version=$xmlVersion",
    "libxml2.sha256=$($properties.libxml2_sha256)",
    "openssl.version=$sslVersion",
    "openssl.sha256=$($properties.openssl_sha256)",
    "tls.patch.sha256=$($properties.ffmpeg_tls_patch_sha256)",
    "security.patch.sha256=$($properties.ffmpeg_security_patch_sha256)",
    "recipe.sha256=$((Get-FileHash -LiteralPath (Join-Path $source 'ffmpeg/cppbuild.sh') -Algorithm SHA256).Hash.ToLowerInvariant())",
    'verification=JNI-original-wrappers,DASH,recursive-entities,libxml2-version,openssl-version,imports-closure,clean-environment,TLS',
    "archive=$classifier",
    "archive.sha256=$((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant())"
)
[IO.File]::WriteAllLines((Join-Path $output 'build.properties'), $lines, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllLines((Join-Path $output 'dependencies.txt'), $imports, [Text.UTF8Encoding]::new($false))
Write-Output "Candidate native archive: $output"
Write-Output 'Native load, DASH and TLS checks passed; review all five classifier results before publishing.'
