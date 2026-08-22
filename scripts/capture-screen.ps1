#Requires -Version 5.1
<#
.SYNOPSIS
    Capture a device screenshot as an intact PNG, and refuse to leave a broken one behind.

.DESCRIPTION
    `adb exec-out screencap -p > out.png` does not work in PowerShell. PowerShell decodes a native
    command's stdout into strings using [Console]::OutputEncoding before the redirect ever sees it,
    so every byte that is not valid text becomes U+FFFD and a BOM is prepended. The result is a
    file of roughly the right size that is not a PNG and cannot be repaired -- the original bytes
    are gone. This repository already contains one such file (39 KB, unreadable), plus a zero-byte
    capture, plus one screenshot saved under three different names as if it were three different
    test states.

    So this script does three things the raw command cannot:
      * captures on the device and pulls the file, keeping the bytes out of the PowerShell pipeline;
      * validates the PNG before keeping it, and deletes it if it is broken or empty;
      * reports when the bytes are identical to an existing capture, so one screen cannot silently
        stand in as evidence for several.

    In Git Bash the plain redirect is binary-safe and the capture path here is unnecessary:
        adb exec-out screencap -p > out.png
    The -Validate mode is still useful there, on a file someone else produced.

.PARAMETER Name
    Label for what is on screen, e.g. `modules` or `home-after-enable`. Becomes the filename
    together with a timestamp.

.PARAMETER Validate
    Check an existing PNG instead of capturing one. Nothing is written or deleted in this mode.

.PARAMETER OutDir
    Directory to write into. Created if missing. Defaults to `screenshots/` at the repo root.

.PARAMETER Serial
    adb serial, when more than one device is attached.

.EXAMPLE
    .\scripts\capture-screen.ps1 -Name modules

.EXAMPLE
    .\scripts\capture-screen.ps1 -Validate .\modules-screen.png

.NOTES
    Exit codes: 0 = the PNG is intact, 1 = capture or validation failed, 2 = usage/no device.
#>
[CmdletBinding(DefaultParameterSetName = 'Capture')]
param(
    [Parameter(Mandatory = $true, ParameterSetName = 'Capture', Position = 0)]
    [string]$Name,
    [Parameter(Mandatory = $true, ParameterSetName = 'Validate')]
    [string]$Validate,
    [string]$OutDir,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'

# --- Validation --------------------------------------------------------------------------------
#
# A file of plausible size is not evidence of anything; the corrupt capture already in this repo is
# 39 KB. Check the signature, walk the chunk list to the end marker, and read the real dimensions.
# Kept as a function so -Validate can reach it without a device: an unrunnable validator is a
# validator nobody checks.

function Get-BeUInt32([byte[]]$b, [int]$at) {
    return ([long]$b[$at] * 16777216) + ([long]$b[$at + 1] * 65536) + ([long]$b[$at + 2] * 256) + [long]$b[$at + 3]
}

function Get-PngInfo([string]$path) {
    $info = @{ Problem = $null; Width = 0; Height = 0; Length = 0 }
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $info.Length = $bytes.Length

    if ($bytes.Length -eq 0) {
        $info.Problem = 'the file is empty'
        return $info
    }
    if ($bytes.Length -lt 24) {
        $info.Problem = "only $($bytes.Length) bytes; too short to be a PNG"
        return $info
    }

    $signature = @(137, 80, 78, 71, 13, 10, 26, 10)
    for ($i = 0; $i -lt 8; $i++) {
        if ($bytes[$i] -ne $signature[$i]) {
            # This is exactly what the text-mode redirect produces, so name the cause rather than
            # just reporting a bad header.
            $info.Problem = 'not a PNG (header mangled -- something decoded the bytes as text)'
            return $info
        }
    }

    $info.Width = Get-BeUInt32 $bytes 16
    $info.Height = Get-BeUInt32 $bytes 20

    # Walk the chunks. A capture interrupted mid-transfer keeps a valid header, so the header alone
    # does not prove the image is whole -- only reaching IEND does.
    $at = 8
    $lastType = ''
    while ($at + 8 -le $bytes.Length) {
        $len = Get-BeUInt32 $bytes $at
        $lastType = [System.Text.Encoding]::ASCII.GetString($bytes, $at + 4, 4)
        if ($at + 12 + $len -gt $bytes.Length) {
            $info.Problem = "truncated inside the '$lastType' chunk"
            return $info
        }
        $at += 12 + $len
    }
    if ($lastType -ne 'IEND') {
        $info.Problem = "no IEND chunk; the stream ends after '$lastType'"
        return $info
    }
    if ($info.Width -eq 0 -or $info.Height -eq 0) {
        $info.Problem = "degenerate dimensions $($info.Width)x$($info.Height)"
    }
    return $info
}

# Three byte-identical files under three names already got recorded here as three different test
# states. The screen genuinely not changing is a legitimate outcome, so this names the twin instead
# of refusing -- but it does not let the duplication go unnoticed.
function Find-IdenticalCapture([string]$path) {
    $dir = Split-Path -Parent (Resolve-Path $path).Path
    $hash = (Get-FileHash -Algorithm SHA256 -Path $path).Hash
    foreach ($existing in Get-ChildItem -Path $dir -Filter '*.png' -File) {
        if ($existing.FullName -eq (Resolve-Path $path).Path) { continue }
        if ((Get-FileHash -Algorithm SHA256 -Path $existing.FullName).Hash -eq $hash) {
            return $existing.Name
        }
    }
    return $null
}

function Show-CaptureRecord([string]$path, $info) {
    $hash = (Get-FileHash -Algorithm SHA256 -Path $path).Hash
    $twin = Find-IdenticalCapture $path
    $leaf = Split-Path -Leaf $path

    Write-Host ''
    Write-Host "  $leaf" -ForegroundColor Green
    Write-Host "  $($info.Width)x$($info.Height), $($info.Length) bytes"
    Write-Host "  sha256 $hash"
    if ($twin) {
        Write-Host "  identical to $twin -- the screen did not change, so do not file these as two results" -ForegroundColor Yellow
    }
    Write-Host ''
    Write-Host 'Record line for docs/TESTING.md:'
    $line = "  $leaf  $($info.Width)x$($info.Height)  sha256 $($hash.Substring(0, 12))"
    if ($twin) { $line = "$line  (identical to $twin)" }
    Write-Host $line
}

# --- Validate-only -----------------------------------------------------------------------------

if ($PSCmdlet.ParameterSetName -eq 'Validate') {
    if (-not (Test-Path -PathType Leaf $Validate)) {
        Write-Host "error: no such file: $Validate" -ForegroundColor Red
        exit 2
    }
    $info = Get-PngInfo (Resolve-Path $Validate).Path
    if ($info.Problem) {
        Write-Host "$(Split-Path -Leaf $Validate): $($info.Problem)" -ForegroundColor Red
        exit 1
    }
    Show-CaptureRecord (Resolve-Path $Validate).Path $info
    exit 0
}

# --- Capture -----------------------------------------------------------------------------------

# The label ends up in a filename, so reject anything that would escape OutDir or produce a name
# the shell has to be quoted to handle.
if ($Name -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') {
    Write-Host "error: -Name must be letters, digits, dot, dash or underscore; got '$Name'" -ForegroundColor Red
    exit 2
}

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $repoRoot 'screenshots' }
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
$OutDir = (Resolve-Path $OutDir).Path

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Host 'error: adb not found in PATH' -ForegroundColor Red
    exit 2
}

# Build the serial prefix once instead of branching at every call site.
$adbArgs = @()
if ($Serial) { $adbArgs = @('-s', $Serial) }

$state = & adb @adbArgs get-state
if ($LASTEXITCODE -ne 0 -or "$state".Trim() -ne 'device') {
    Write-Host "error: no device ready (adb get-state said '$state')" -ForegroundColor Red
    Write-Host 'hint: adb devices' -ForegroundColor Yellow
    exit 2
}

# Capture into /data/local/tmp rather than /sdcard: it is always writable by the shell user and does
# not depend on external storage being mounted, which on this fork's target (an XTC watch ROM) it
# sometimes is not.
$devicePath = '/data/local/tmp/lspd-capture.png'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outFile = Join-Path $OutDir "$Name-$stamp.png"

& adb @adbArgs shell screencap -p $devicePath
if ($LASTEXITCODE -ne 0) {
    Write-Host 'error: screencap failed on the device' -ForegroundColor Red
    exit 1
}

# `adb pull` writes the file itself, so the bytes never pass through PowerShell's text decoding --
# which is the whole reason this goes via the device instead of using exec-out.
& adb @adbArgs pull $devicePath $outFile | Out-Null
$pullFailed = ($LASTEXITCODE -ne 0)
& adb @adbArgs shell rm -f $devicePath | Out-Null

if ($pullFailed -or -not (Test-Path $outFile)) {
    Write-Host 'error: adb pull failed' -ForegroundColor Red
    if (Test-Path $outFile) { Remove-Item $outFile -Force }
    exit 1
}

$info = Get-PngInfo $outFile
if ($info.Problem) {
    Write-Host "error: $($info.Problem)" -ForegroundColor Red
    Write-Host "       discarding $outFile rather than leaving it as evidence" -ForegroundColor Red
    Remove-Item $outFile -Force
    exit 1
}

Show-CaptureRecord $outFile $info
exit 0
