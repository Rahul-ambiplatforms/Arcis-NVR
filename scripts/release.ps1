<#
.SYNOPSIS
    Bump version, collect release notes, commit, tag, and push to trigger the
    GitHub Actions release pipeline.

.PARAMETER Version
    New version name (e.g. "1.2.0"). Omit to only bump the versionCode.

.PARAMETER VersionCode
    Explicit versionCode integer. Defaults to (current + 1).

.PARAMETER Notes
    Release notes as a single string. If omitted you will be prompted to type
    them interactively (finish with a blank line).

.PARAMETER DryRun
    Show what would happen without making any changes.

.EXAMPLE
    .\scripts\release.ps1 -Version 1.2.0
    .\scripts\release.ps1 -Version 1.2.0 -Notes "Fixed login crash. Added dark mode."
    .\scripts\release.ps1 -DryRun
#>

param(
    [string] $Version     = "",
    [int]    $VersionCode = 0,
    [string] $Notes       = "",
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot   = Split-Path -Parent $PSScriptRoot
$GradleFile = Join-Path $RepoRoot "app\build.gradle.kts"

if (-not (Test-Path $GradleFile)) {
    Write-Error "Cannot find app/build.gradle.kts at: $GradleFile"
    exit 1
}

$content = Get-Content $GradleFile -Raw

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

# Collect release notes interactively if not passed via -Notes
if ($Notes -eq "") {
    Write-Host "Enter release notes (what changed / what was fixed)." -ForegroundColor Yellow
    Write-Host "Type each item and press Enter. Press Enter on a blank line when done." -ForegroundColor Yellow
    Write-Host ""
    $lines = @()
    while ($true) {
        $line = Read-Host "  >"
        if ($line -eq "") { break }
        $lines += $line
    }
    $Notes = $lines -join "`n"
}

if ($Notes -eq "") {
    Write-Host "No release notes entered. Aborted." -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Release notes:" -ForegroundColor Cyan
$Notes -split "`n" | ForEach-Object { Write-Host "  $_" }
Write-Host ""

$confirm = Read-Host "Proceed with release ${tag}? (y/N)"
if ($confirm -notmatch '^[Yy]$') {
    Write-Host "Aborted." -ForegroundColor Yellow
    exit 0
}

# Update versionCode and versionName in build.gradle.kts
$updated = [regex]::Replace($content, 'versionCode(\s*=\s*)\d+', "versionCode`${1}$newCode")
$updated = [regex]::Replace($updated, 'versionName(\s*=\s*)"[^"]+"', 'versionName${1}"' + $newName + '"')

Set-Content -Path $GradleFile -Value $updated -NoNewline -Encoding UTF8
Write-Host "Updated app/build.gradle.kts" -ForegroundColor Green

Push-Location $RepoRoot
try {
    git add app/build.gradle.kts
    git commit -m "chore: release $tag"

    # Annotated tag — the message becomes the GitHub Release body
    $tagMessage = "Release $tag`n`n$Notes"
    git tag -a $tag -m $tagMessage

    git push
    git push origin $tag

    Write-Host ""
    Write-Host "Pushed tag $tag - GitHub Actions will build and publish the release APK." -ForegroundColor Green

    $remote = git remote get-url origin
    if ($remote -match 'github\.com[:/](.+?)(\.git)?$') {
        $slug = $Matches[1]
        Write-Host "Track progress: https://github.com/$slug/actions" -ForegroundColor Cyan
    }
}
finally {
    Pop-Location
}
