[CmdletBinding()]
param(
    [ValidateSet('Check', 'Stage', 'Bundle')][string]$Mode = 'Bundle',
    [string]$ProjectRoot = ''
)

. (Join-Path $PSScriptRoot 'windows-build-common.ps1')
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
if ($env:OS -ne 'Windows_NT' -or $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') {
    throw 'Windows x64 PowerShell에서 실행하세요. ARM64 빌드는 아직 지원하지 않습니다.'
}
$vsDevCmd = Get-PkbVsDevCmd
$visualCppRuntime = Get-PkbVisualCppRuntime
foreach ($command in @('git.exe', 'node.exe', 'npm.cmd', 'cargo.exe', 'rustup.exe')) {
    if ($null -eq (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "개발 도구가 없습니다: $command. docs/development/fork-and-build.md를 확인하세요."
    }
}
$nodeVersion = (& node.exe --version | Out-String).Trim()
if ($nodeVersion -notmatch '^v24\.') { throw "Node.js 24 LTS가 필요합니다. 현재: $nodeVersion" }
Write-Host "개발 도구 확인 완료: Node $nodeVersion / MSVC $vsDevCmd"
if ($Mode -eq 'Check') { return }

$dependencies = & (Join-Path $PSScriptRoot 'prepare-windows-dependencies.ps1') -ProjectRoot $projectPath
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
Push-Location $projectPath
try {
    $env:JAVA_HOME = $dependencies.JavaHome
    $env:PATH = (Join-Path $dependencies.JavaHome 'bin') + ';' + $oldPath
    & (Join-Path $PSScriptRoot 'build-pgvector-windows.ps1') -ProjectRoot $projectPath -PostgreSqlRoot $dependencies.PostgreSqlRoot -SourceRoot $dependencies.PgvectorSource -Version $dependencies.PgvectorVersion
    if ($Mode -eq 'Bundle') {
        Invoke-PkbCommand (Join-Path $projectPath 'gradlew.bat') @('--no-daemon', 'test', 'bootJar')
    }
    $stageArguments = @{
        ProjectRoot = $projectPath
        JavaHome = $dependencies.JavaHome
        PostgreSqlRoot = $dependencies.PostgreSqlRoot
        PgvectorRoot = Join-Path $projectPath 'build\pgvector-windows'
        TesseractRoot = $dependencies.TesseractRoot
        TessdataRoot = $dependencies.TessdataRoot
        VisualCppRuntimeRoot = $visualCppRuntime
        SkipBackendBuild = ($Mode -eq 'Bundle')
    }
    & (Join-Path $PSScriptRoot 'stage-windows-runtime.ps1') @stageArguments
    & (Join-Path $PSScriptRoot 'test-windows-runtime.ps1') -ProjectRoot $projectPath
    if ($Mode -eq 'Stage') { return }

    & (Join-Path $PSScriptRoot 'test-windows-build.ps1') -ProjectRoot $projectPath
    Push-Location (Join-Path $projectPath 'frontend')
    try {
        Invoke-PkbCommand 'npm.cmd' @('ci', '--no-audit', '--no-fund')
        Invoke-PkbCommand 'npm.cmd' @('test')
        Invoke-PkbCommand 'cargo.exe' @('test', '--locked', '--manifest-path', 'src-tauri/Cargo.toml')
        Invoke-PkbCommand 'npm.cmd' @('run', 'desktop', '--', 'build', '--bundles', 'nsis', '--', '--locked')
    } finally { Pop-Location }
    $config = Get-Content (Join-Path $projectPath 'frontend\src-tauri\tauri.conf.json') -Raw | ConvertFrom-Json
    $installer = Join-Path $projectPath ("frontend\src-tauri\target\release\bundle\nsis\PrivateKB_{0}_x64-setup.exe" -f $config.version)
    $hash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $([IO.Path]::GetFileName($installer))" | Set-Content -LiteralPath ($installer + '.sha256') -Encoding ASCII
    [ordered]@{
        applicationVersion = $config.version
        dependencyLockSha256 = $dependencies.LockHash
        installerSha256 = $hash
        node = $nodeVersion
        rust = (& rustc.exe --version | Out-String).Trim()
        createdAtUtc = [DateTime]::UtcNow.ToString('o')
        checks = @('Java 단위 테스트', '배포 Java Office 파싱', '독립 DB·pgvector·API 기동', '원본 위치 인증·네이티브 연결', '관리형 로그 수집·회전·안전 종료', '설치·삭제 안전 종료', '빌드 스크립트', '프런트엔드', 'Rust')
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath ($installer + '.build.json') -Encoding UTF8
    Write-Host "설치 파일 생성 완료: $installer"
    Write-Host '현재 PC의 설치본과 사용자 데이터는 변경하지 않았습니다.'
} finally {
    Pop-Location
    $env:JAVA_HOME = $oldJavaHome
    $env:PATH = $oldPath
}
