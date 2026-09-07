param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('windows-x86_64', 'linux-x86_64', 'linux-arm64', 'macosx-x86_64', 'macosx-arm64')]
    [string] $Platform,
    [ValidateSet('java_test_version', 'java_compat_version')]
    [string] $VersionProperty = 'java_test_version',
    [string] $ProjectRoot
)

$ErrorActionPreference = 'Stop'
$moduleRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = $moduleRoot }
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
$versions = @(Get-Content -LiteralPath (Join-Path $ProjectRoot 'gradle.properties') |
        Where-Object { $_.StartsWith($VersionProperty + '=') })
if ($versions.Count -ne 1) { throw "Expected one $VersionProperty in the selected project's gradle.properties" }
$version = $versions[0].Substring($VersionProperty.Length + 1).Trim()
if ($version -notmatch '^\d+\.\d+\.\d+(\.\d+)?$') { throw "Invalid pinned Java version: $version" }
$key = 'java_' + $version.Replace('.', '_') + '_' + $Platform.Replace('-', '_') + '_sha256'
$checksums = @(Get-Content -LiteralPath (Join-Path $moduleRoot 'gradle.properties') |
        Where-Object { $_.StartsWith($key + '=') })
if ($checksums.Count -ne 1) { throw "Missing reviewed Microsoft archive checksum: $key" }
$expected = $checksums[0].Substring($key.Length + 1).Trim()
if ($expected -notmatch '^[a-f0-9]{64}$') { throw "Invalid Microsoft archive checksum: $key" }

$archivePlatform = switch ($Platform) {
    'windows-x86_64' { 'windows-x64' }
    'linux-x86_64' { 'linux-x64' }
    'linux-arm64' { 'linux-aarch64' }
    'macosx-x86_64' { 'macos-x64' }
    'macosx-arm64' { 'macos-aarch64' }
}
$architecture = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
$expectedArchitecture = if ($Platform.EndsWith('arm64')) { 'Arm64' } else { 'X64' }
$matchingOS = ($IsWindows -and $Platform.StartsWith('windows-')) -or
        ($IsLinux -and $Platform.StartsWith('linux-')) -or ($IsMacOS -and $Platform.StartsWith('macosx-'))
if (!$matchingOS -or $architecture -ne $expectedArchitecture) { throw "Run $Platform Java on its matching host" }

$extension = if ($IsWindows) { 'zip' } else { 'tar.gz' }
$filename = "microsoft-jdk-$version-$archivePlatform.$extension"
$directory = Join-Path $ProjectRoot 'build/toolchains'
[IO.Directory]::CreateDirectory($directory) | Out-Null
$archive = Join-Path $directory $filename
if (!(Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -Uri "https://aka.ms/download-jdk/$filename" -OutFile $archive -TimeoutSec 300
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $expected) {
    throw "Microsoft JDK archive SHA-256 mismatch: $filename"
}

# FRESH EXTRACTION AVOIDS REUSING EDITED FILES OR REPLACING A JDK THAT ANOTHER PROCESS STILL USES.
$installation = Join-Path $directory ("java-$version-$Platform-" + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($installation) | Out-Null
if ($extension -eq 'zip') {
    Expand-Archive -LiteralPath $archive -DestinationPath $installation
} else {
    & tar -xzf $archive -C $installation
    if ($LASTEXITCODE -ne 0) { throw 'Microsoft JDK extraction failed' }
}
$homes = @()
foreach ($folder in Get-ChildItem -LiteralPath $installation -Directory) {
    $candidate = if ($IsMacOS) { Join-Path $folder.FullName 'Contents/Home' } else { $folder.FullName }
    if (Test-Path -LiteralPath (Join-Path $candidate 'release')) { $homes += $candidate }
}
if ($homes.Count -ne 1) { throw 'Expected exactly one JDK home in the Microsoft archive' }
$javaHome = $homes[0]
$release = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $javaHome 'release')) {
    if ($line -match '^([^=]+)="(.*)"$') { $release[$Matches[1]] = $Matches[2] }
}
$releaseArchitecture = if ($expectedArchitecture -eq 'Arm64') { '^(aarch64|arm64)$' } else { '^(x86_64|amd64)$' }
if ($release.JAVA_VERSION -ne $version -or $release.IMPLEMENTOR -ne 'Microsoft' -or $release.OS_ARCH -notmatch $releaseArchitecture) {
    throw 'Extracted JDK vendor, exact version or architecture disagrees with the selected archive'
}
$suffix = if ($IsWindows) { '.exe' } else { '' }
$java = Join-Path $javaHome "bin/java$suffix"
$javac = Join-Path $javaHome "bin/javac$suffix"
if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $javac)) { throw 'Microsoft archive lacks the JDK executables' }
& $java -version
if ($LASTEXITCODE -ne 0) { throw 'The verified Microsoft JDK could not start' }
& $javac -version
if ($LASTEXITCODE -ne 0) { throw 'The verified Microsoft Java compiler could not start' }

# ONLY THE CURRENT PROCESS AND FOLLOWING WORKFLOW STEPS USE THIS JDK; SYSTEM INSTALLATIONS ARE UNCHANGED.
$env:JAVA_HOME = $javaHome
$javaBin = Join-Path $javaHome 'bin'
$env:PATH = $javaBin + [IO.Path]::PathSeparator + $env:PATH
if ($env:GITHUB_ENV) { Add-Content -LiteralPath $env:GITHUB_ENV -Value "JAVA_HOME=$javaHome" }
if ($env:GITHUB_PATH) { Add-Content -LiteralPath $env:GITHUB_PATH -Value $javaBin }
Write-Output "Verified Microsoft JDK $($release.JAVA_RUNTIME_VERSION) for $Platform in $javaHome"
