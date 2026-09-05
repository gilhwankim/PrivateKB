[CmdletBinding()]
param(
    [string]$ProjectRoot = '',
    [string]$PostgreSqlRoot = '',
    [string]$BuildToolsRoot = '',
    [string]$SourceRoot = '',
    [string]$Version = '0.8.6'
)

. (Join-Path $PSScriptRoot 'windows-build-common.ps1')
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
$vsDevCmd = Get-PkbVsDevCmd $BuildToolsRoot
if ([string]::IsNullOrWhiteSpace($PostgreSqlRoot) -or [string]::IsNullOrWhiteSpace($SourceRoot)) {
    $dependencies = & (Join-Path $PSScriptRoot 'prepare-windows-dependencies.ps1') -ProjectRoot $projectPath
    if ([string]::IsNullOrWhiteSpace($PostgreSqlRoot)) { $PostgreSqlRoot = $dependencies.PostgreSqlRoot }
    if ([string]::IsNullOrWhiteSpace($SourceRoot)) { $SourceRoot = $dependencies.PgvectorSource }
    if ($Version -ne $dependencies.PgvectorVersion) { throw '고정 파일의 pgvector 버전과 일치해야 합니다.' }
}
$postgresPath = (Resolve-Path -LiteralPath $PostgreSqlRoot).Path
if (-not (Test-Path -LiteralPath (Join-Path $postgresPath 'include\server\postgres.h'))) {
    throw "PostgreSQL 서버 개발 헤더가 없습니다: $postgresPath"
}
$buildPath = Join-Path $projectPath 'build'
$sourcePath = Join-Path $buildPath ('pgvector-build-' + [Guid]::NewGuid().ToString('N'))
$outputPath = Join-Path $buildPath 'pgvector-windows'
Assert-PkbChildPath $projectPath $sourcePath
Assert-PkbChildPath $buildPath $outputPath
New-Item -ItemType Directory -Path $buildPath -Force | Out-Null
try {
    # 캐시 원본을 수정하지 않고 매번 새 작업 폴더에서 빌드한다.
    Copy-Item -LiteralPath $SourceRoot -Destination $sourcePath -Recurse
    $makefile = Get-Content -LiteralPath (Join-Path $sourcePath 'Makefile.win') -Raw
    if ($makefile -notmatch ('(?m)^EXTVERSION = ' + [regex]::Escape($Version) + '\s*$')) {
        throw 'pgvector 소스와 요청 버전이 다릅니다.'
    }
    # cmd.exe에는 빌드만 맡긴다. 파일 삭제·이동은 검증 후 PowerShell에서 처리한다.
    foreach ($path in @($vsDevCmd, $postgresPath, $sourcePath)) {
        if ($path -match '["%&!^\r\n]') { throw '빌드 경로에는 %, &, !, ^ 또는 큰따옴표를 사용할 수 없습니다.' }
    }
    $buildCommand = 'call "{0}" -arch=amd64 -host_arch=amd64 >nul && set "PGROOT={1}" && nmake /F Makefile.win' -f $vsDevCmd, $postgresPath
    Push-Location $sourcePath
    try { Invoke-PkbCommand 'cmd.exe' @('/d', '/c', $buildCommand) }
    finally { Pop-Location }
    $vectorDll = Join-Path $sourcePath 'vector.dll'
    $generatedSql = Join-Path $sourcePath "sql\vector--$Version.sql"
    if (-not (Test-Path -LiteralPath $vectorDll) -or -not (Test-Path -LiteralPath $generatedSql)) {
        throw 'pgvector 빌드 산출물이 없습니다.'
    }
    Remove-PkbGeneratedPath $buildPath $outputPath
    New-Item -ItemType Directory -Path (Join-Path $outputPath 'lib') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $outputPath 'share\extension') -Force | Out-Null
    Copy-Item -LiteralPath $vectorDll -Destination (Join-Path $outputPath 'lib\vector.dll')
    Copy-Item -LiteralPath (Join-Path $sourcePath 'vector.control') -Destination (Join-Path $outputPath 'share\extension')
    Get-ChildItem -LiteralPath (Join-Path $sourcePath 'sql') -Filter 'vector--*.sql' -File |
        Copy-Item -Destination (Join-Path $outputPath 'share\extension')
    Copy-Item -LiteralPath (Join-Path $sourcePath 'LICENSE') -Destination $outputPath
    Write-Host "Windows용 pgvector $Version 빌드 완료: $outputPath"
} finally {
    Remove-PkbGeneratedPath $buildPath $sourcePath
}
