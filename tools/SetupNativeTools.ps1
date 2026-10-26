param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('windows-x86_64', 'linux-x86_64', 'linux-arm64', 'macosx-x86_64', 'macosx-arm64')]
    [string] $Platform
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$properties = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $root 'gradle.properties')) {
    if ($line -match '^([^#!\s][^=]*)=(.*)$') { $properties[$Matches[1]] = $Matches[2] }
}
$directory = Join-Path $root 'build/native-tools'
[IO.Directory]::CreateDirectory($directory) | Out-Null
$maven = $properties.maven_version
$archive = Join-Path $directory "apache-maven-$maven-bin.zip"
if (!(Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$maven/apache-maven-$maven-bin.zip" -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash -ine $properties.maven_sha512) { throw 'Maven archive SHA-512 mismatch' }
$mavenDirectory = Join-Path $directory "apache-maven-$maven"
if (!(Test-Path -LiteralPath $mavenDirectory)) { Expand-Archive -LiteralPath $archive -DestinationPath $directory }

$cmake = $properties.cmake_version
$cmakePlatform = switch ($Platform) {
    'windows-x86_64' { 'windows-x86_64' }
    'linux-x86_64' { 'linux-x86_64' }
    'linux-arm64' { 'linux-aarch64' }
    default { 'macos-universal' }
}
$suffix = if ($Platform.StartsWith('windows-')) { 'zip' } else { 'tar.gz' }
$name = "cmake-$cmake-$cmakePlatform.$suffix"
$checksums = Join-Path $directory "cmake-$cmake-SHA-256.txt"
Invoke-WebRequest "https://github.com/Kitware/CMake/releases/download/v$cmake/cmake-$cmake-SHA-256.txt" -OutFile $checksums
if ((Get-FileHash -LiteralPath $checksums -Algorithm SHA256).Hash -ine $properties.cmake_manifest_sha256) { throw 'CMake checksum manifest changed' }
$expected = (Get-Content -LiteralPath $checksums | Where-Object { ($_ -split '\s+', 2)[1] -eq $name }) -split '\s+', 2
if ($expected.Count -ne 2 -or $expected[0] -notmatch '^[a-fA-F0-9]{64}$') { throw 'CMake artifact checksum is missing' }
$archive = Join-Path $directory $name
if (!(Test-Path -LiteralPath $archive)) { Invoke-WebRequest "https://github.com/Kitware/CMake/releases/download/v$cmake/$name" -OutFile $archive }
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $expected[0]) { throw 'CMake archive SHA-256 mismatch' }
$cmakeDirectory = Join-Path $directory "cmake-$cmake-$cmakePlatform"
if (!(Test-Path -LiteralPath $cmakeDirectory)) {
    if ($suffix -eq 'zip') { Expand-Archive -LiteralPath $archive -DestinationPath $directory }
    else {
        & tar -xzf $archive -C $directory
        if ($LASTEXITCODE -ne 0) { throw 'CMake extraction failed' }
    }
}
$cmakeBin = if ($Platform.StartsWith('macosx-')) { Join-Path $cmakeDirectory 'CMake.app/Contents/bin' } else { Join-Path $cmakeDirectory 'bin' }
$mavenBin = Join-Path $mavenDirectory 'bin'
$bins = @($cmakeBin, $mavenBin)

# THE DISTRIBUTION MESON IS TOO OLD FOR libplacebo ON LINUX; meson.py RUNS IN PLACE THROUGH A bin/meson LINK
if ($Platform.StartsWith('linux-')) {
    $meson = $properties.meson_version
    $archive = Join-Path $directory "meson-$meson.tar.gz"
    if (!(Test-Path -LiteralPath $archive)) { Invoke-WebRequest "https://github.com/mesonbuild/meson/releases/download/$meson/meson-$meson.tar.gz" -OutFile $archive }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $properties.meson_sha256) { throw 'Meson archive SHA-256 mismatch' }
    $mesonDirectory = Join-Path $directory "meson-$meson"
    if (!(Test-Path -LiteralPath $mesonDirectory)) {
        & tar -xzf $archive -C $directory
        if ($LASTEXITCODE -ne 0) { throw 'Meson extraction failed' }
    }
    $mesonBin = Join-Path $mesonDirectory 'bin'
    if (!(Test-Path -LiteralPath (Join-Path $mesonBin 'meson'))) {
        [IO.Directory]::CreateDirectory($mesonBin) | Out-Null
        New-Item -ItemType SymbolicLink -Path (Join-Path $mesonBin 'meson') -Target (Join-Path $mesonDirectory 'meson.py') | Out-Null
    }
    $bins += $mesonBin
}
$env:PATH = ($bins -join [IO.Path]::PathSeparator) + [IO.Path]::PathSeparator + $env:PATH
if ($env:GITHUB_PATH) { foreach ($bin in $bins) { Add-Content $env:GITHUB_PATH $bin } }
Write-Output "Verified Maven $maven, CMake $cmake$(if ($meson) { " and Meson $meson" }) in $directory"
