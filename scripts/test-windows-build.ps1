[CmdletBinding()]
param([string]$ProjectRoot = '')
. (Join-Path $PSScriptRoot 'windows-build-common.ps1')
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
$build = Join-Path $projectPath 'build'
$testRoot = Join-Path $build ('build-script-test-' + [Guid]::NewGuid().ToString('N'))
Assert-PkbChildPath $projectPath $testRoot
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$script:passed = 0
function Test-Rejected([scriptblock]$Action, [string]$Pattern) {
    $message = ''
    try { & $Action | Out-Null } catch { $message = $_.Exception.Message }
    if ($message -notmatch $Pattern) { throw "거부 테스트 실패. 예상: $Pattern / 실제: $message" }
    $script:passed++
}
try {
    # 데이터 폴더 제외 규칙이 Java의 storage 패키지까지 숨겼던 문제를 방지한다.
    $storageSource = 'src/main/java/io/privatekb/platform/internal/storage/LocalContentStorage.java'
    if (-not (Test-Path -LiteralPath (Join-Path $projectPath $storageSource))) { throw '필수 storage 소스가 복사본에 없습니다.' }
    $ignoreProbe = Join-Path $testRoot 'ignore-probe'
    New-Item -ItemType Directory -Path $ignoreProbe | Out-Null
    Invoke-PkbCommand 'git.exe' @('init', '--quiet', $ignoreProbe)
    Copy-Item -LiteralPath (Join-Path $projectPath '.gitignore') -Destination $ignoreProbe
    Push-Location $ignoreProbe
    try {
        & git check-ignore --quiet $storageSource
        if ($LASTEXITCODE -eq 0) { throw '필수 storage 소스가 .gitignore에서 제외됩니다.' }
        if ($LASTEXITCODE -gt 1) { throw 'Git 제외 규칙 검사에 실패했습니다.' }
        foreach ($privatePath in @('.env', 'data/privatekb/file', 'storage/file', '.build-deps/file', 'build/file', 'frontend/src-tauri/runtime/app/privatekb.jar')) {
            & git check-ignore --quiet $privatePath
            if ($LASTEXITCODE -ne 0) { throw "비공개·생성 파일이 Git에서 제외되지 않습니다: $privatePath" }
        }
    } finally { Pop-Location }
    $script:passed++
    foreach ($scriptFile in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File) {
        $tokens = $null
        $errors = $null
        [Management.Automation.Language.Parser]::ParseFile($scriptFile.FullName, [ref]$tokens, [ref]$errors) | Out-Null
        if ($errors.Count -ne 0) { throw "PowerShell 구문 오류: $($scriptFile.Name) $errors" }
    }
    $script:passed++
    $tauriConfig = Get-Content -LiteralPath (Join-Path $projectPath 'frontend\src-tauri\tauri.conf.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($tauriConfig.bundle.windows.nsis.installerHooks -ne 'installer-hooks.nsh') {
        throw 'NSIS 안전 종료 훅이 설치 설정에 연결되지 않았습니다.'
    }
    $installerHooks = Get-Content -LiteralPath (Join-Path $projectPath 'frontend\src-tauri\installer-hooks.nsh') -Raw -Encoding UTF8
    foreach ($requiredHookText in @(
        'NSIS_HOOK_PREINSTALL',
        'NSIS_HOOK_PREUNINSTALL',
        '--privatekb-maintenance-shutdown',
        'CheckIfAppIsRunning',
        'FindProcessCurrentUser',
        '$R2 >= 60',
        'pg_ctl.exe',
        '-m fast -w -t 30'
    )) {
        if (-not $installerHooks.Contains($requiredHookText)) {
            throw "NSIS 안전 종료 훅의 필수 동작이 없습니다: $requiredHookText"
        }
    }
    $script:passed++
    Test-Rejected { Assert-PkbChildPath $testRoot $testRoot } '허용된 작업 폴더'
    Test-Rejected { Assert-PkbChildPath $testRoot ($testRoot + '-other\file') } '허용된 작업 폴더'
    Test-Rejected { Assert-PkbChildPath $testRoot (Join-Path $testRoot '..\outside') } '허용된 작업 폴더'
    Assert-PkbChildPath $testRoot (Join-Path $testRoot 'nested\file')
    $script:passed++
    $file = Join-Path $testRoot 'fixture.txt'
    [IO.File]::WriteAllText($file, 'PrivateKB build fixture', [Text.Encoding]::UTF8)
    $hash = (Get-FileHash -LiteralPath $file).Hash
    Assert-PkbHash $file $hash
    $script:passed++
    Test-Rejected { Assert-PkbHash $file ('0' * 64) } 'SHA-256 불일치'
    $artifact = [PSCustomObject]@{ file = 'fixture.txt'; url = 'https://example.invalid/fixture'; sha256 = $hash }
    $cached = Get-PkbDownload $artifact $testRoot -Offline
    if ($cached -ne $file) { throw '검증된 오프라인 캐시를 사용하지 않았습니다.' }
    $script:passed++
    $artifact.sha256 = '0' * 64
    Test-Rejected { Get-PkbDownload $artifact $testRoot -Offline } 'SHA-256 불일치'
    $artifact.file = 'missing.zip'
    Test-Rejected { Get-PkbDownload $artifact $testRoot -Offline } '오프라인 캐시가 없습니다'
    $artifact.url = 'http://example.invalid/fixture'
    Test-Rejected { Get-PkbDownload $artifact $testRoot } 'HTTPS만'
    $artifact.file = '..\escape.zip'
    Test-Rejected { Get-PkbDownload $artifact $testRoot } '파일명에 경로'
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = Join-Path $testRoot 'unsafe.zip'
    $archive = [IO.Compression.ZipFile]::Open($zip, [IO.Compression.ZipArchiveMode]::Create)
    try { $archive.CreateEntry('../escape.txt') | Out-Null } finally { $archive.Dispose() }
    Test-Rejected { Expand-PkbZip $zip (Join-Path $testRoot 'unpack') 'must-not-run.exe' } '허용된 작업 폴더'
    if (Test-Path -LiteralPath (Join-Path $testRoot 'escape.txt')) { throw '압축 경로 이탈이 발생했습니다.' }
    Write-Host "빌드 스크립트 검증 통과: $script:passed 건"
} finally {
    Remove-PkbGeneratedPath $build $testRoot
}
