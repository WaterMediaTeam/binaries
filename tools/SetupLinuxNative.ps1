param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('linux-x86_64', 'linux-arm64')]
    [string] $Platform,
    [switch] $PrepareOnly
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$properties = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $root 'gradle.properties')) {
    if ($line -match '^([^#!\s][^=]*)=(.*)$') { $properties[$Matches[1]] = $Matches[2] }
}
foreach ($name in @('vulkan_headers', 'userland')) {
    if ($properties["${name}_ref"] -notmatch '^[a-f0-9]{40}$' -or $properties["${name}_sha256"] -notmatch '^[a-f0-9]{64}$') {
        throw "Missing pinned $name source revision or SHA-256"
    }
}
if ($properties.vulkan_headers_version -notmatch '^\d+\.\d+\.\d+$') { throw 'Missing Vulkan headers version' }
if (!$PrepareOnly) {
    $architecture = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
    $expected = if ($Platform -eq 'linux-arm64') { 'Arm64' } else { 'X64' }
    if (!$IsLinux -or $architecture -ne $expected) { throw "Run $Platform preparation on its matching Linux host" }
}

$work = Join-Path $root 'build/linux-native'
$downloads = Join-Path $work 'downloads'
$generation = Join-Path $work ('source-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($downloads) | Out-Null
[IO.Directory]::CreateDirectory($generation) | Out-Null
$sources = @(
    @{ Name = 'Vulkan-Headers'; Key = 'vulkan_headers'; Repository = 'KhronosGroup/Vulkan-Headers' }
)
if ($Platform -eq 'linux-arm64') {
    $sources += @{ Name = 'userland'; Key = 'userland'; Repository = 'raspberrypi/userland' }
}
foreach ($source in $sources) {
    $revision = $properties[($source.Key + '_ref')]
    $archive = Join-Path $downloads ($source.Name + '-' + $revision + '.tar.gz')
    if (!(Test-Path -LiteralPath $archive)) {
        Invoke-WebRequest -Uri "https://github.com/$($source.Repository)/archive/$revision.tar.gz" -OutFile $archive -TimeoutSec 180
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $properties[($source.Key + '_sha256')]) {
        throw "Source archive SHA-256 mismatch: $($source.Name)"
    }
    # A FRESH SOURCE DIRECTORY PREVENTS AN EARLIER BUILD'S GENERATED FILES FROM ALTERING THE NEXT BUILD.
    & tar -xzf $archive -C $generation
    if ($LASTEXITCODE -ne 0) { throw "Cannot extract verified $($source.Name) source" }
}
$headers = Join-Path $generation ('Vulkan-Headers-' + $properties.vulkan_headers_ref + '/include')
$version = $properties.vulkan_headers_version.Split('.')
$header = Get-Content -LiteralPath (Join-Path $headers 'vulkan/vulkan_core.h') -Raw
if ($header -notmatch "(?m)^#define VK_HEADER_VERSION $($version[2])\r?$" -or
        $header -notmatch "VK_MAKE_API_VERSION\(0, $($version[0]), $($version[1]), VK_HEADER_VERSION\)") {
    throw 'Verified source does not contain the configured Vulkan header version'
}
Write-Output "Verified Vulkan headers $($properties.vulkan_headers_version): $headers"
if ($PrepareOnly) {
    Write-Output "Verified source preparation only; no native compilation or environment publication: $generation"
    return
}

if ($Platform -eq 'linux-arm64') {
    $userland = Join-Path $generation ('userland-' + $properties.userland_ref)
    $build = Join-Path $userland 'build/raspberry/release'
    # MATCH THE UPSTREAM ARM64 BUILD WITHOUT ITS GLOBAL INSTALL OR UNCHECKED COMMAND EXIT CODES.
    & cmake -S $userland -B $build -DCMAKE_BUILD_TYPE=Release -DARM64=ON
    if ($LASTEXITCODE -ne 0) { throw 'ARM userland configuration failed' }
    & cmake --build $build --parallel ([Math]::Max(1, [Environment]::ProcessorCount - 2))
    if ($LASTEXITCODE -ne 0) { throw 'ARM userland compilation failed' }
    foreach ($library in @('libvchiq_arm.so', 'libvcos.so', 'libbcm_host.so')) {
        $path = Join-Path $userland "build/lib/$library"
        $stream = [IO.File]::OpenRead($path)
        try {
            $elf = [byte[]]::new(20)
            $stream.ReadExactly($elf, 0, $elf.Length)
        } finally { $stream.Dispose() }
        if ($elf[0] -ne 127 -or $elf[1] -ne 69 -or $elf[2] -ne 76 -or $elf[3] -ne 70 -or
                $elf[4] -ne 2 -or $elf[5] -ne 1 -or ($elf[18] + 256 * $elf[19]) -ne 183) {
            throw "ARM userland output is not a little-endian AArch64 ELF library: $library"
        }
        Write-Output "$library SHA-256: $((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant())"
    }
    $env:USERLAND_PATH = $userland
    if ($env:GITHUB_ENV) { Add-Content -LiteralPath $env:GITHUB_ENV -Value "USERLAND_PATH=$userland" }
}

# GCC/CLANG SEARCH CPATH BEFORE SYSTEM HEADERS EVEN WHEN THE RECIPE REPLACES PKG_CONFIG_PATH.
$env:CPATH = if ($env:CPATH) { $headers + [IO.Path]::PathSeparator + $env:CPATH } else { $headers }
if ($env:GITHUB_ENV) { Add-Content -LiteralPath $env:GITHUB_ENV -Value "CPATH=$env:CPATH" }
Write-Output "Prepared Linux native dependencies for $Platform"
