<#
.SYNOPSIS
    Build the LSPosed Magisk modules (Riru + Zygisk) on Windows.

.DESCRIPTION
    LSPosed needs two libxposed artifacts in the local Maven repository before it
    can compile, and neither is published publicly at the version LSPosed HEAD
    wants. This script materialises them: it clones each dependency at a pinned
    commit, applies the patches needed to build against our toolchain, publishes
    them to mavenLocal, then builds both flavors.

    Mirrors scripts/build.sh. See docs/BUILDING.md for the toolchain requirements.

.PARAMETER BuildType
    debug (default) or release. Release builds need signing config in
    gradle.properties.

.PARAMETER Flavor
    all (default), riru, or zygisk.

.PARAMETER ForceDeps
    Re-clone and re-publish the libxposed dependencies even if cached.

.EXAMPLE
    .\scripts\build.ps1
    Builds both flavors, debug.

.EXAMPLE
    .\scripts\build.ps1 -BuildType release
    Builds both flavors, release.
#>
[CmdletBinding()]
param(
    [ValidateSet('debug', 'release')]
    [string]$BuildType = 'debug',

    [ValidateSet('all', 'riru', 'zygisk')]
    [string]$Flavor = 'all',

    [switch]$ForceDeps
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$DepsDir = Join-Path $Root '.deps'

# Pinned dependency commits.
#
# Both libxposed repos kept their published version at "100" while continuing to
# evolve, and their `100` tags sit on very early commits. The tag alone does not
# give the API shape LSPosed HEAD compiles against, so these are chosen by API
# shape rather than version number.
#
#   api @5458273    - later commits add invokeOrigin/invokeSpecial Constructor
#                     overloads that LSPosedContext does not implement.
#   service @tag100 - buildable as-is (Gradle 7.6), but its AIDL predates the
#     + ee4c516 aidl  interface LSPModuleService implements, so we take only the
#                     newer AIDL. ee4c516 and not the later e58452c, which adds
#                     getRunningTargets() that LSPModuleService lacks.
#
# Both URLs can be overridden to build without hitting the network, e.g. against
# local mirrors (which must contain the pinned commits):
#   $env:LIBXPOSED_API_REPO = 'C:\mirrors\api'; .\scripts\build.ps1
$ApiRepo = if ($env:LIBXPOSED_API_REPO) { $env:LIBXPOSED_API_REPO } else { 'https://github.com/libxposed/api.git' }
$ApiCommit = '5458273'
$ServiceRepo = if ($env:LIBXPOSED_SERVICE_REPO) { $env:LIBXPOSED_SERVICE_REPO } else { 'https://github.com/libxposed/service.git' }
$ServiceTag = '100'
$ServiceAidlCommit = 'ee4c516'
$ServiceAidlPath = 'interface/src/main/aidl/io/github/libxposed/service/IXposedService.aidl'

function Write-Step([string]$Message) {
    Write-Host ''
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Invoke-Checked([string]$Description, [scriptblock]$Action) {
    & $Action
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE"
    }
}

# Network fetches fail intermittently; a single blip should not abort a build that
# has already spent minutes publishing the other dependency.
function Invoke-Retried([string]$Description, [scriptblock]$Action) {
    $attempts = 3
    $delay = 5
    for ($n = 1; $n -le $attempts; $n++) {
        & $Action
        if ($LASTEXITCODE -eq 0) { return }
        if ($n -eq $attempts) {
            throw "$Description failed after $attempts attempts (exit code $LASTEXITCODE)"
        }
        Write-Host "  attempt $n failed, retrying in ${delay}s..." -ForegroundColor Yellow
        Start-Sleep -Seconds $delay
        $delay *= 2
    }
}

# Rewrite a file in place, preserving its original encoding-free byte semantics
# well enough for these ASCII build scripts.
function Edit-File([string]$Path, [hashtable]$Replacements) {
    $content = Get-Content -Raw -Path $Path
    foreach ($key in $Replacements.Keys) {
        $content = $content.Replace($key, $Replacements[$key])
    }
    Set-Content -Path $Path -Value $content -NoNewline -Encoding utf8
}

# The cloned dependencies are separate Gradle builds and cannot see this
# project's local.properties, so give each one its own. Without this they fail
# with "SDK location not found" on any machine that relies on local.properties
# rather than ANDROID_HOME.
#
# Must run after `git clean`, which would otherwise delete the file we write.
function Set-SdkLocation([string]$Dir) {
    $target = Join-Path $Dir 'local.properties'
    $rootProps = Join-Path $Root 'local.properties'

    if (Test-Path $rootProps) {
        $sdkLine = Get-Content $rootProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($sdkLine) {
            Set-Content -Path $target -Value $sdkLine -Encoding utf8
            return
        }
    }

    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $null }
    if ($sdk) {
        # Gradle reads local.properties as a Java properties file, where '\' is
        # an escape character; use forward slashes so Windows paths survive.
        Set-Content -Path $target -Value "sdk.dir=$($sdk -replace '\\', '/')" -Encoding utf8
    }
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw "'git' not found in PATH"
}

# --- Preflight -------------------------------------------------------------

$localProps = Join-Path $Root 'local.properties'
if (-not (Test-Path $localProps) -and -not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    throw @'
No Android SDK configured.

Either set ANDROID_HOME / ANDROID_SDK_ROOT, or create local.properties with:

    sdk.dir=C:/Android/Sdk        # forward slashes, even on Windows

Required components: Platform 34, Build-Tools 34.0.0,
NDK 26.1.10909125, CMake 3.22.1, and JDK 17.
'@
}

# The native build reads submodules directly; an empty external/ fails deep
# inside CMake with an unhelpful error, so check up front.
if (-not (Test-Path (Join-Path $Root 'external/lsplant/README.md'))) {
    Write-Step 'Fetching git submodules'
    Invoke-Checked 'git submodule update' { git -C $Root submodule update --init --recursive }
}

# --- libxposed dependencies ------------------------------------------------

# The marker records which commits the cached artifacts came from, so bumping a
# pin above re-publishes instead of silently reusing a stale artifact.
$DepsMarker = Join-Path $DepsDir '.published'
$DepsStamp = "api=$ApiCommit service=$ServiceTag+$ServiceAidlCommit"

# The published artifacts live in the Maven local repository, which is outside the tree the
# marker travels in. A .deps/ copied from another machine therefore arrives with a marker
# claiming work that was never done here, and the build then fails deep inside dependency
# resolution with nothing pointing back to the stale stamp. Require the artifacts themselves.
$MavenLocal = if ($env:MAVEN_REPO_LOCAL) { $env:MAVEN_REPO_LOCAL }
              else { Join-Path $env:USERPROFILE '.m2/repository' }
# Forward slashes on purpose: PowerShell accepts them on Windows, and they survive being
# copied between shells and editors intact, unlike a path full of escapes.
$DepsArtifacts = @(
    'io/github/libxposed/api/100/api-100.aar'
    'io/github/libxposed/api/100/api-100.pom'
    'io/github/libxposed/interface/100/interface-100.aar'
    'io/github/libxposed/interface/100/interface-100.pom'
) | ForEach-Object { Join-Path $MavenLocal $_ }

if ($ForceDeps -and (Test-Path $DepsDir)) {
    Write-Step 'ForceDeps set, discarding cached dependencies'
    Remove-Item -Recurse -Force $DepsDir
}

# Trim a UTF-8 BOM as well as whitespace: Windows PowerShell 5.1's Set-Content -Encoding utf8
# used to write one here, and build.sh could not match it. Both scripts now write the bare
# stamp, and both read tolerantly so an older marker still compares equal.
$depsStampOnDisk = if (Test-Path $DepsMarker) {
    (Get-Content -Raw $DepsMarker).TrimStart([char]0xFEFF).Trim()
} else { $null }

$missingArtifacts = @($DepsArtifacts | Where-Object { -not (Test-Path $_) })
$depsCurrent = ($depsStampOnDisk -eq $DepsStamp) -and ($missingArtifacts.Count -eq 0)

if (($depsStampOnDisk -eq $DepsStamp) -and ($missingArtifacts.Count -gt 0)) {
    Write-Step "Marker is current but $($missingArtifacts.Count) artifact(s) are missing from $MavenLocal; re-publishing"
    foreach ($m in $missingArtifacts) { Write-Host "  missing: $m" }
}

if ($depsCurrent) {
    Write-Step "libxposed dependencies already published ($DepsStamp)"
}
else {
    New-Item -ItemType Directory -Force -Path $DepsDir | Out-Null

    Write-Step "Preparing io.github.libxposed:api:100 @$ApiCommit"
    $apiDir = Join-Path $DepsDir 'api'
    if (-not (Test-Path (Join-Path $apiDir '.git'))) {
        if (Test-Path $apiDir) { Remove-Item -Recurse -Force $apiDir }
        Invoke-Retried 'git clone api' { git clone $ApiRepo $apiDir }
    }
    Invoke-Retried 'git fetch api' { git -C $apiDir fetch --all --tags --quiet }
    Invoke-Checked 'git checkout api' { git -C $apiDir checkout --quiet --force $ApiCommit }
    git -C $apiDir clean -xfdq -e build -e .gradle

    # Upstream targets JDK 21; we build on 17. api and its checks subproject must
    # agree or Gradle fails with "Inconsistent JVM-target compatibility". These
    # are interface/lint-only libraries, so the downgrade is safe.
    foreach ($f in @('api/build.gradle.kts', 'checks/build.gradle.kts')) {
        Edit-File (Join-Path $apiDir $f) @{ 'JavaVersion.VERSION_21' = 'JavaVersion.VERSION_17' }
    }

    Set-SdkLocation $apiDir

    Push-Location $apiDir
    try { Invoke-Checked 'publish api' { & '.\gradlew.bat' :api:publishToMavenLocal } }
    finally { Pop-Location }

    Write-Step "Preparing io.github.libxposed:interface:100 @$ServiceTag + $ServiceAidlCommit AIDL"
    $serviceDir = Join-Path $DepsDir 'service'
    if (-not (Test-Path (Join-Path $serviceDir '.git'))) {
        if (Test-Path $serviceDir) { Remove-Item -Recurse -Force $serviceDir }
        Invoke-Retried 'git clone service' { git clone $ServiceRepo $serviceDir }
    }
    Invoke-Retried 'git fetch service' { git -C $serviceDir fetch --all --tags --quiet }
    Invoke-Checked 'git checkout service' { git -C $serviceDir checkout --quiet --force $ServiceTag }
    git -C $serviceDir clean -xfdq -e build -e .gradle
    # Take only the AIDL from the newer commit; the rest of tag 100 builds cleanly.
    Invoke-Checked 'git checkout aidl' {
        git -C $serviceDir checkout $ServiceAidlCommit -- $ServiceAidlPath
    }

    # Tag 100 pins compileSdk 33 / Build-Tools 33.0.1, which may not be installed.
    # 34 is what the rest of this build already requires.
    foreach ($f in @('interface/build.gradle.kts', 'service/build.gradle.kts')) {
        Edit-File (Join-Path $serviceDir $f) @{
            'compileSdk = 33'                = 'compileSdk = 34'
            'buildToolsVersion = "33.0.1"'   = 'buildToolsVersion = "34.0.0"'
        }
    }

    Set-SdkLocation $serviceDir

    Push-Location $serviceDir
    try { Invoke-Checked 'publish interface' { & '.\gradlew.bat' :interface:publishToMavenLocal } }
    finally { Pop-Location }

    # WriteAllText with a BOM-less encoding, not Set-Content -Encoding utf8: on Windows
    # PowerShell 5.1 the latter prepends a UTF-8 BOM and appends CRLF, which build.sh could
    # never match, so a bash build after a PowerShell build always re-published from scratch.
    [System.IO.File]::WriteAllText($DepsMarker, $DepsStamp, (New-Object System.Text.UTF8Encoding $false))
}

# --- LSPosed ---------------------------------------------------------------

$bt = (Get-Culture).TextInfo.ToTitleCase($BuildType)
$tasks = switch ($Flavor) {
    'all' { @(":magisk-loader:zipRiru$bt", ":magisk-loader:zipZygisk$bt") }
    'riru' { @(":magisk-loader:zipRiru$bt") }
    'zygisk' { @(":magisk-loader:zipZygisk$bt") }
}

# Both flavors are built in one invocation on purpose. app/ and daemon/ are not
# flavor-specific, so building them together guarantees the two zips ship an
# identical manager.apk — building one flavor now and the other after an edit is
# how the flavors drift apart.
Write-Step "Building LSPosed ($BuildType, $Flavor)"
Push-Location $Root
try { Invoke-Checked 'gradle build' { & '.\gradlew.bat' @tasks } }
finally { Pop-Location }

Write-Step 'Artifacts in magisk-loader/release/'
Get-ChildItem (Join-Path $Root 'magisk-loader/release/*.zip') -ErrorAction SilentlyContinue |
    ForEach-Object { '{0,8:N0} KB  {1}' -f ($_.Length / 1KB), $_.Name }

if ($Flavor -eq 'all') {
    $verify = Join-Path $PSScriptRoot 'verify-parity.sh'
    if ((Test-Path $verify) -and (Get-Command bash -ErrorAction SilentlyContinue)) {
        Write-Step 'Verifying Riru/Zygisk parity'
        & bash $verify $BuildType
        if ($LASTEXITCODE -ne 0) { throw 'Parity check failed' }
    }
    else {
        Write-Host ''
        Write-Host 'Skipping parity check (needs bash; it ships with Git for Windows).' -ForegroundColor Yellow
    }
}

# Record the build's identity while the tree is still in the state that produced it; see the header
# of release-manifest.sh for why. Delegated to bash rather than reimplemented here, so the two build
# scripts cannot drift on what a manifest contains. Non-fatal: a missing manifest does not make the
# artifacts wrong.
$manifest = Join-Path $PSScriptRoot 'release-manifest.sh'
if ((Test-Path $manifest) -and (Get-Command bash -ErrorAction SilentlyContinue)) {
    Write-Step 'Recording release manifest'
    & bash $manifest $BuildType
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'Manifest generation failed (artifacts are unaffected).' -ForegroundColor Yellow
    }
}
else {
    Write-Host ''
    Write-Host 'Skipping release manifest (needs bash; it ships with Git for Windows).' -ForegroundColor Yellow
}
