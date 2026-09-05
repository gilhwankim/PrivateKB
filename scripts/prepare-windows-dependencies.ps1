[CmdletBinding()]
param([string]$ProjectRoot = '', [switch]$Offline)

. (Join-Path $PSScriptRoot 'windows-build-common.ps1')
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
if (-not [Environment]::Is64BitOperatingSystem -or $env:OS -ne 'Windows_NT') {
    throw '이 준비 스크립트는 Windows x64용입니다.'
}
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
$lockFile = Join-Path $projectPath 'scripts\windows-dependencies.lock.json'
$lock = Get-Content -LiteralPath $lockFile -Raw -Encoding UTF8 | ConvertFrom-Json
if ($lock.schemaVersion -ne 1) { throw '지원하지 않는 의존성 고정 파일입니다.' }
$lockHash = (Get-FileHash -LiteralPath $lockFile -Algorithm SHA256).Hash.ToLowerInvariant()
$root = Join-Path $projectPath '.build-deps'
$cache = Join-Path $root 'downloads'
$prepared = Join-Path $root $lockHash.Substring(0, 16)
Assert-PkbChildPath $projectPath $root
Assert-PkbChildPath $root $prepared
New-Item -ItemType Directory -Path $root -Force | Out-Null
# 동시 실행으로 같은 캐시를 덮어쓰지 않는다. 잠금은 프로세스 종료 시 해제된다.
$guard = [IO.File]::Open((Join-Path $root 'prepare.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$stage = Join-Path $root ('stage-' + [Guid]::NewGuid().ToString('N'))
try {
    $downloads = @{}
    foreach ($property in $lock.artifacts.PSObject.Properties) {
        $downloads[$property.Name] = Get-PkbDownload $property.Value $cache -Offline:$Offline
    }
    $inventoryPath = Join-Path $prepared 'inventory.json'
    if (Test-Path -LiteralPath $inventoryPath) {
        $inventory = Get-Content -LiteralPath $inventoryPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($inventory.lockHash -ne $lockHash) { throw '준비된 의존성의 버전 정보가 일치하지 않습니다.' }
        $actualFiles = @(Get-ChildItem -LiteralPath $prepared -Recurse -File | Where-Object { $_.FullName -ne $inventoryPath })
        if (@($inventory.files).Count -eq 0 -or $actualFiles.Count -ne @($inventory.files).Count) {
            throw '준비된 캐시 파일 목록이 변경되었습니다. 해당 생성 캐시를 확인하세요.'
        }
        foreach ($file in $inventory.files) {
            $candidate = Join-Path $prepared $file.path
            Assert-PkbChildPath $prepared $candidate
            Assert-PkbHash $candidate $file.sha256
        }
        Write-Host '프로젝트 전용 의존성 캐시 검증 완료'
    } else {
        if (Test-Path -LiteralPath $prepared) { throw "미완성 캐시입니다. 이 생성 폴더를 확인 후 제거하세요: $prepared" }
        New-Item -ItemType Directory -Path $stage | Out-Null
        $sevenZipRoot = Join-Path $stage '7zip'
        # 설치 EXE를 실행하지 않는다. 공식 7zr로 필요한 콘솔 파일만 추출한다.
        Invoke-PkbCommand $downloads.sevenZipBootstrap @('x', $downloads.sevenZip, "-o$sevenZipRoot", '-y', '-bso0', '-bsp0', '7z.exe', '7z.dll', 'License.txt')
        $sevenZip = Join-Path $sevenZipRoot '7z.exe'
        Expand-PkbZip $downloads.jdk (Join-Path $stage 'jdk') $sevenZip
        Expand-PkbZip $downloads.postgresql (Join-Path $stage 'postgresql') $sevenZip @('pgsql\bin\*', 'pgsql\lib\*', 'pgsql\share\*', 'pgsql\include\*', 'pgsql\server_license.txt', 'pgsql\commandlinetools_3rd_party_licenses.txt')
        Expand-PkbZip $downloads.pgvector (Join-Path $stage 'pgvector') $sevenZip
        $ocrRoot = Join-Path $stage 'ocr'
        Invoke-PkbCommand $sevenZip @('x', $downloads.tesseract, "-o$ocrRoot", '-y', '-bso0', '-bsp0', 'tesseract.exe', '*.dll', 'doc\LICENSE')
        $tessdata = Join-Path $ocrRoot 'tessdata'
        New-Item -ItemType Directory -Path $tessdata -Force | Out-Null
        Copy-Item -LiteralPath $downloads.ocrEnglish,$downloads.ocrKorean,$downloads.ocrDataLicense -Destination $tessdata
        Copy-Item -LiteralPath (Join-Path $ocrRoot 'doc\LICENSE') -Destination (Join-Path $tessdata 'tesseract-LICENSE')
        $files = @(Get-ChildItem -LiteralPath $stage -Recurse -File | ForEach-Object {
            [ordered]@{ path = $_.FullName.Substring($stage.Length + 1); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
        })
        [ordered]@{ lockHash = $lockHash; files = $files } | ConvertTo-Json -Depth 5 |
            Set-Content -LiteralPath (Join-Path $stage 'inventory.json') -Encoding UTF8
        Assert-PkbChildPath $root $stage
        Move-Item -LiteralPath $stage -Destination $prepared
        Write-Host '프로젝트 전용 의존성 준비 완료 (시스템 설치 없음)'
    }
    [PSCustomObject]@{
        JavaHome = Join-Path $prepared ('jdk\' + $lock.artifacts.jdk.root)
        PostgreSqlRoot = Join-Path $prepared 'postgresql\pgsql'
        PgvectorSource = Join-Path $prepared ('pgvector\' + $lock.artifacts.pgvector.root)
        PgvectorVersion = $lock.artifacts.pgvector.version
        TesseractRoot = Join-Path $prepared 'ocr'
        TessdataRoot = Join-Path $prepared 'ocr\tessdata'
        LockHash = $lockHash
    }
} finally {
    try {
        if (Test-Path -LiteralPath $stage) { Remove-PkbGeneratedPath $root $stage }
    } finally {
        $guard.Dispose()
    }
}
