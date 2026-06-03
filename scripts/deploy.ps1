# ============================================================
#  SafeAlert 버전 배포 스크립트 (GitHub Releases + Firebase DB)
#
#  사용법:
#    .\scripts\deploy.ps1 -version "1.1.0" -changelog "버그 수정"
#    .\scripts\deploy.ps1 -version "1.2.0" -changelog "긴급 패치" -force $true
#
#  최초 1회 설정 필요:
#    gh auth login          (GitHub 로그인)
#    firebase login         (Firebase 로그인)
#    firebase use safealert-98d7e  (Firebase 프로젝트 연결)
# ============================================================

param(
    [Parameter(Mandatory=$true)]
    [string]$version,

    [string]$changelog = "",

    [bool]$force = $false
)

$ErrorActionPreference = "Stop"
$ROOT     = Split-Path $PSScriptRoot -Parent
$DEBUG_APK = "$ROOT\app\build\outputs\apk\debug\app-debug.apk"
$APK_NAME  = "safealert-v$version.apk"

function Write-Step($msg) { Write-Host "`n▶ $msg" -ForegroundColor Cyan }
function Write-OK($msg)   { Write-Host "  ✓ $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "  ⚠ $msg" -ForegroundColor Yellow }
function Write-Err($msg)  { Write-Host "  ✗ $msg" -ForegroundColor Red; exit 1 }

Write-Host "`n========================================" -ForegroundColor White
Write-Host "  SafeAlert 배포  v$version" -ForegroundColor White
Write-Host "========================================" -ForegroundColor White

# ── 사전 확인 ──────────────────────────────────────────────
Write-Step "0/5  환경 확인"

$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Write-Err "GitHub CLI 없음. winget install GitHub.cli 실행 후 재시도" }
# Firebase CLI 불필요 — REST API 사용

# GitHub 로그인 확인
$ghStatus = gh auth status 2>&1
if ($LASTEXITCODE -ne 0) { Write-Err "GitHub 로그인 필요: gh auth login 실행 후 재시도" }

# GitHub 저장소 확인
$repoInfo = gh repo view safealert-releases --json nameWithOwner 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Warn "저장소 없음 → 자동 생성"
    gh repo create safealert-releases --private --description "SafeAlert APK releases"
    if ($LASTEXITCODE -ne 0) { Write-Err "저장소 생성 실패" }
    Write-OK "저장소 생성 완료"
} else {
    Write-OK "저장소 확인: safealert-releases"
}

$ghUser = (gh api user --jq .login 2>$null).Trim()
$APK_URL = "https://github.com/$ghUser/safealert-releases/releases/download/v$version/$APK_NAME"
Write-OK "배포 URL 예정: $APK_URL"

# ── 1. 소스 버전 상수 업데이트 ──────────────────────────────
Write-Step "1/5  소스 버전 업데이트"
$updateManagerPath = "$ROOT\app\src\main\java\com\wf11\safealert\06_utils\UpdateManager.kt"
$content = Get-Content $updateManagerPath -Raw -Encoding UTF8
$content = $content -replace 'const val CURRENT_VERSION = ".*?"', "const val CURRENT_VERSION = `"$version`""
Set-Content $updateManagerPath -Value $content -Encoding UTF8 -NoNewline
Write-OK "CURRENT_VERSION = `"$version`""

# ── 2. APK 빌드 ──────────────────────────────────────────────
Write-Step "2/5  APK 빌드"
Push-Location $ROOT
try {
    & .\gradlew.bat assembleDebug 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Err "빌드 실패. gradlew.bat assembleDebug 직접 실행해서 오류 확인" }
} finally { Pop-Location }

if (-not (Test-Path $DEBUG_APK)) { Write-Err "APK 파일 없음: $DEBUG_APK" }
$apkSize = [math]::Round((Get-Item $DEBUG_APK).Length / 1MB, 1)
Write-OK "빌드 완료 (${apkSize}MB)"

# ── 3. GitHub Release 생성 + APK 업로드 ──────────────────────
Write-Step "3/5  GitHub Release 생성 + APK 업로드"

# 기존 릴리즈 삭제 (재배포 시)
$existing = gh release view "v$version" --repo "$ghUser/safealert-releases" 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Warn "v$version 릴리즈 이미 존재 → 덮어쓰기"
    gh release delete "v$version" --repo "$ghUser/safealert-releases" --yes 2>$null
}

# APK 파일 이름 변경 (버전 포함)
$tempApk = "$env:TEMP\$APK_NAME"
Copy-Item $DEBUG_APK $tempApk -Force

$releaseNotes = if ($changelog) { $changelog } else { "SafeAlert v$version" }
gh release create "v$version" $tempApk `
    --repo "$ghUser/safealert-releases" `
    --title "SafeAlert v$version" `
    --notes $releaseNotes

if ($LASTEXITCODE -ne 0) { Write-Err "GitHub Release 업로드 실패" }
Write-OK "업로드 완료: $APK_URL"
Remove-Item $tempApk -ErrorAction SilentlyContinue

# ── 4. Firebase DB 버전 정보 업데이트 (REST API — CLI 로그인 불필요) ──
Write-Step "4/5  Firebase DB 버전 정보 등록"

$DB_URL = "https://safealert-98d7e-default-rtdb.firebaseio.com/wf11/version.json"

$versionData = @{
    latest       = $version
    apk_url      = $APK_URL
    changelog    = $changelog
    force_update = $force
    updated_at   = (Get-Date -Format "yyyy-MM-dd HH:mm")
}

try {
    $response = Invoke-RestMethod -Uri $DB_URL -Method PUT `
        -Body ($versionData | ConvertTo-Json -Compress) `
        -ContentType "application/json" -TimeoutSec 10
    Write-OK "DB 등록 완료"
    Write-Host "    latest: $version" -ForegroundColor Gray
    Write-Host "    changelog: $changelog" -ForegroundColor Gray
} catch {
    Write-Warn "DB 등록 실패: $($_.Exception.Message)"
    Write-Host "  Firebase 콘솔에서 Realtime Database 활성화 여부 확인" -ForegroundColor Gray
    Write-Host "  수동 등록 URL: $DB_URL" -ForegroundColor Gray
    Write-Host "  데이터: $($versionData | ConvertTo-Json -Compress)" -ForegroundColor Gray
}

# ── 5. 완료 ──────────────────────────────────────────────────
Write-Step "5/5  배포 완료"
Write-Host @"

  버전:          v$version
  APK URL:       $APK_URL
  강제 업데이트:  $force
  변경 내용:     $changelog

  앱 사용자가 다음에 앱을 열면 업데이트 팝업이 표시됩니다.
"@ -ForegroundColor White
