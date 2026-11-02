# Publishes the version in VERSION: builds both installers, tags v<VERSION>, pushes to GitHub (origin) and the NAS (nas),
# and creates the GitHub release with that version's CHANGELOG section and both installers.
# Run from anywhere:  pwsh tools\release.ps1
# Needs: VERSION bumped, a "## <VERSION> — <date>" section in CHANGELOG.md, everything committed.
# GitHub login: the one git already stores (Git Credential Manager); the token is never printed.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$ver = (Get-Content VERSION -Raw).Trim()
$tag = "v$ver"
$repo = 'hparksy/PaddleTrainer'

# --- checks before anything leaves this PC
if (git status --porcelain) { throw 'Uncommitted changes: commit first.' }
$log = Get-Content CHANGELOG.md -Raw -Encoding UTF8
$m = [regex]::Match($log, "(?ms)^## $([regex]::Escape($ver)) .*?\r?\n(.*?)(?=^## |\z)")
if (-not $m.Success) { throw "CHANGELOG.md has no '## $ver — <date>' section." }
if (git tag --list $tag) { throw "Tag $tag already exists: bump VERSION." }

# --- build
cmd /c tools\build_all.bat
if ($LASTEXITCODE) { throw 'Build failed.' }
$assets = @(  # fixed names: releases/latest/download/<name> (README, INSTALL) always gets the newest version
    @{ path = "dist\PaddleTrainer-Setup-$ver.exe"; name = 'PaddleTrainer-Setup.exe'; type = 'application/vnd.microsoft.portable-executable' },
    @{ path = "dist\PaddleTrainer-$ver.apk"; name = 'PaddleTrainer.apk'; type = 'application/vnd.android.package-archive' })

# --- tag and push
git tag -a $tag -m "Paddle Trainer $ver"
git push origin main $tag
git push nas main $tag

# --- GitHub release
$cred = "protocol=https`nhost=github.com`n`n" | git credential fill
$token = ($cred | Where-Object { $_ -like 'password=*' }) -replace '^password=', ''
$h = @{ Authorization = "Bearer $token"; 'User-Agent' = 'git'; Accept = 'application/vnd.github+json' }
$dl = "https://github.com/$repo/releases/download/$tag"
$notes = @"
## 다운로드

| 기기 | 받기 |
|---|---|
| 🖥️ Windows 10/11 (64비트) | **[⬇ PaddleTrainer-Setup.exe]($dl/PaddleTrainer-Setup.exe)** |
| 📱 Android 8.0 이상 | **[⬇ PaddleTrainer.apk]($dl/PaddleTrainer.apk)** |

📖 처음 설치하면 **[설치 방법](https://github.com/$repo/blob/main/INSTALL.md)**을 보세요.
- Windows에서 'PC 보호' 경고가 뜨면 **추가 정보 → 실행**을 누르세요.
- Android에서 경고가 뜨면 **출처를 알 수 없는 앱 허용**을 켜고, **그래도 설치**를 누르세요.

## 변경 사항

$($m.Groups[1].Value.Trim())
"@
$body = @{ tag_name = $tag; name = "Paddle Trainer $ver"; body = $notes } | ConvertTo-Json
$rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$repo/releases" -Headers $h `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$upload = $rel.upload_url -replace '\{.*\}$', ''
foreach ($a in $assets) {
    $up = Invoke-RestMethod -Method Post -Uri "$($upload)?name=$($a.name)" -Headers $h -ContentType $a.type -InFile $a.path
    "asset $($up.browser_download_url) ($($up.size) bytes)"
}
"released $($rel.html_url)"
