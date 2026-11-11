# Builds a signed AAFin release on this PC and publishes it to GitHub.
#
#   pwsh -File release.ps1
#
# Before running: bump versionCode and versionName in app/build.gradle.kts, add the matching
# "vX.Y — date — notes" line to the top of ChangeLog.txt, and commit. The signing key never
# leaves this machine: only the finished APK is uploaded.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$env:JAVA_HOME = 'C:\Users\Ben\Android\jdk17\jdk-17.0.20.1+1'

$version = (Select-String -Path app/build.gradle.kts -Pattern 'versionName = "(.+)"').Matches[0].Groups[1].Value
$tag = "v$version"
Write-Host "Releasing AAFin $version" -ForegroundColor Cyan

# Safety checks: committed, on main, tag unused, notes written, signing set up.
# Line-ending-only differences (CRLF vs LF on Windows) don't count as changes.
git diff --ignore-cr-at-eol --quiet HEAD
if ($LASTEXITCODE -ne 0 -or (git ls-files --others --exclude-standard)) { throw 'Uncommitted changes. Commit them first.' }
if ((git branch --show-current) -ne 'main') { throw 'Not on main.' }
if (git tag --list $tag) { throw "$tag already exists. Bump versionName first." }
$notes = Get-Content ChangeLog.txt -Encoding utf8 | Where-Object { $_ -like "$tag *" } | Select-Object -First 1
if (-not $notes) { throw "No ChangeLog.txt line starting '$tag'. Add one first." }
if (-not (Test-Path keystore.properties)) { throw 'keystore.properties missing: cannot sign.' }

# Test, then build the signed APK.
& ./gradlew.bat --quiet testDebugUnitTest assembleRelease
if ($LASTEXITCODE -ne 0) { throw 'Tests or build failed.' }

New-Item -ItemType Directory -Force releases | Out-Null
$apk = "releases/AAFin-$tag.apk"
Copy-Item app/build/outputs/apk/release/app-release.apk $apk -Force

# Tag, push, publish.
git tag -a $tag -m "AAFin $version"
# Borrow the GitHub CLI's login for this push only (no change to global git settings).
git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push origin main $tag
if ($LASTEXITCODE -ne 0) { throw 'Push failed.' }
$body = ($notes -replace '^v[\d.]+ — [\d/]+ — ', '') + "`n`nInstall: download **AAFin-$tag.apk** below and open it on the phone. Already installed? AAFin offers this update itself."
gh release create $tag $apk --title "AAFin $version" --notes $body
if ($LASTEXITCODE -ne 0) { throw 'GitHub release failed.' }

Write-Host "Done: https://github.com/BBBannon/AAFin/releases/tag/$tag" -ForegroundColor Green
