$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$properties = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $root 'gradle.properties')) {
    if ($line -match '^([^#!\s][^=]*)=(.*)$') { $properties[$Matches[1]] = $Matches[2] }
}
if ($properties.userland_ref -notmatch '^[a-f0-9]{40}$' -or $properties.userland_sha256 -notmatch '^[a-f0-9]{64}$') {
    throw 'Missing pinned userland source revision or SHA-256'
}
$architecture = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
if (!$IsLinux -or $architecture -ne 'Arm64') { throw 'Run the linux-arm64 preparation on a Linux ARM64 host' }

$work = Join-Path $root 'build/linux-native'
$downloads = Join-Path $work 'downloads'
$generation = Join-Path $work ('source-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($downloads) | Out-Null
[IO.Directory]::CreateDirectory($generation) | Out-Null
$archive = Join-Path $downloads ('userland-' + $properties.userland_ref + '.tar.gz')
if (!(Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -Uri "https://github.com/raspberrypi/userland/archive/$($properties.userland_ref).tar.gz" -OutFile $archive -TimeoutSec 180
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $properties.userland_sha256) {
    throw 'Source archive SHA-256 mismatch: userland'
}
# A FRESH SOURCE DIRECTORY PREVENTS AN EARLIER BUILD'S GENERATED FILES FROM ALTERING THE NEXT BUILD.
& tar -xzf $archive -C $generation
if ($LASTEXITCODE -ne 0) { throw 'Cannot extract verified userland source' }

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
Write-Output 'Prepared the Raspberry Pi userland for linux-arm64'
