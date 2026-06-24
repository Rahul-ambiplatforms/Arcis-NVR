<#
.SYNOPSIS
    Bump version, commit, tag, and push to trigger the GitHub Actions release pipeline.

.PARAMETER Version
    New version name (e.g. "1.2.0"). Omit to only bump the versionCode.

.PARAMETER VersionCode
    Explicit versionCode integer. Defaults to (current + 1).

.PARAMETER DryRun
    Show what would happen without making any changes.

.EXAMPLE
    .\scripts\release.ps1 -Version 1.2.0
    .\scripts\release.ps1 -Version 1.2.0 -VersionCode 5
    .\scripts\release.ps1 -DryRun
#>

param(
    [string] $Version    = "",
    [int]    $VersionCode = 0,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ── Paths ──────────────────────────────────────────────────────────────────────
$RepoRoot   = Split-Path -Parent $PSScriptRoot
$GradleFile = Join-Path $RepoRoot "app\build.gradle.kts"

if (-not (Test-Path $GradleFile)) {
    Write-Error "Cannot find app/build.gradle.kts at: $GradleFile"
    exit 1
}

$content = Get-Content $GradleFile -Raw

# ── Read current values ────────────────────────────────────────────────────────
if ($content -match 'versionCode\s*=\s*(\d+)') {
    $currentCode = [int]$Matches[1]
} else {
    Write-Error "Could not parse versionCode from build.gradle.kts"
    exit 1
}

if ($content -match 'versionName\s*=\s*"([^"]+)"') {
    $currentName = $Matches[1]
} else {
    Write-Error "Could not parse versionName from build.gradle.kts"
    exit 1
}

# ── Resolve new values ─────────────────────────────────────────────────────────
$newName = if ($Version -ne "") { $Version } else { $currentName }
$newCode = if ($VersionCode -gt 0) { $VersionCode } else { $currentCode + 1 }
$tag     = "v$newName"

Write-Host ""
Write-Host "=== ArcisAI NVR Release ===" -ForegroundColor Cyan
Write-Host "  versionCode : $currentCode  ->  $newCode"
Write-Host "  versionName : $currentName  ->  $newName"
Write-Host "  Git tag     : $tag"
if ($DryRun) { Write-Host "  [DRY RUN - no changes will be made]" -ForegroundColor Yellow }
Write-Host ""

if ($DryRun) { exit 0 }

# ── Confirm ────────────────────────────────────────────────────────────────────
$confirm = Read-Host "Proceed? (y/N)"
if ($confirm -notmatch '^[Yy]$') {
    Write-Host "Aborted." -ForegroundColor Yellow
    exit 0
}

# ── Update build.gradle.kts ───────────────────────────────────────────────────
$updated = $content `
    -replace '(versionCode\s*=\s*)\d+',    "`${1}$newCode" `
    -replace '(versionName\s*=\s*)"[^"]+"', "`${1}`"$newName`""

Set-Content -Path $GradleFile -Value $updated -NoNewline
Write-Host "Updated app/build.gradle.kts" -ForegroundColor Green

# ── Git commit + tag + push ───────────────────────────────────────────────────
Push-Location $RepoRoot
try {
    git add app/build.gradle.kts
    git commit -m "chore: release $tag"
    git tag $tag
    git push
    git push origin $tag

    Write-Host ""
    Write-Host "Pushed tag $tag — GitHub Actions will build and publish the release APK." -ForegroundColor Green
    Write-Host "Track progress: https://github.com/$(git remote get-url origin | Select-String -Pattern 'github\.com[:/](.+?)(?:\.git)?$' | ForEach-Object { $_.Matches[0].Groups[1].Value })/actions" -ForegroundColor Cyan
}
finally {
    Pop-Location
}
