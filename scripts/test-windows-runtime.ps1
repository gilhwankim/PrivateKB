[CmdletBinding()]
param([string]$ProjectRoot = '')

. (Join-Path $PSScriptRoot 'windows-build-common.ps1')
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
$runtime = Join-Path $projectPath 'frontend\src-tauri\runtime'
$build = Join-Path $projectPath 'build'
$probe = Join-Path $build ('runtime-smoke-' + [Guid]::NewGuid().ToString('N'))
Assert-PkbChildPath $projectPath $probe
New-Item -ItemType Directory -Path $probe -Force | Out-Null
$database = Join-Path $probe 'database'
$pgBin = Join-Path $runtime 'postgresql\bin'
$java = Join-Path $runtime 'java\bin\java.exe'
$jar = Join-Path $runtime 'app\privatekb.jar'
$backend = $null
$oldEnvironment = @{}
function Set-ProbeEnvironment([string]$Name, [string]$Value) {
    if (-not $oldEnvironment.ContainsKey($Name)) {
        $oldEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
function Clear-ProbeEnvironment([string]$Name) {
    if (-not $oldEnvironment.ContainsKey($Name)) {
        $oldEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    # 빈 문자열은 libpq에서 명시적인 빈 파일 경로로 해석될 수 있으므로 변수를 제거한다.
    Remove-Item -LiteralPath "Env:$Name" -ErrorAction SilentlyContinue
}
function Get-ProbePort {
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}
function Invoke-ProbePgControl([string[]]$Arguments) {
    # PostgreSQL 자식이 콘솔 파이프를 상속해 PowerShell이 대기하지 않도록 파일로 분리한다.
    $quoted = @($Arguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' })
    $process = Start-Process -FilePath (Join-Path $pgBin 'pg_ctl.exe') -ArgumentList $quoted -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $probe 'pgctl.out.log') -RedirectStandardError (Join-Path $probe 'pgctl.err.log')
    $process.Handle | Out-Null
    if (-not $process.WaitForExit(35000)) {
        Stop-Process -Id $process.Id
        throw '검증용 PostgreSQL 제어 명령의 제한 시간을 초과했습니다.'
    }
    if ($process.ExitCode -ne 0) { throw "검증용 PostgreSQL 제어 실패. 로그: $probe" }
}
try {
    $password = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    $passwordFile = Join-Path $probe 'test-password'
    [IO.File]::WriteAllText($passwordFile, $password, [Text.Encoding]::ASCII)
    foreach ($name in @('PGSERVICE', 'PGSERVICEFILE', 'PGOPTIONS')) { Clear-ProbeEnvironment $name }
    Set-ProbeEnvironment 'PGPASSWORD' $password
    Set-ProbeEnvironment 'PGCLIENTENCODING' 'UTF8'
    Invoke-PkbCommand (Join-Path $pgBin 'initdb.exe') @('-D', $database, '-U', 'privatekb', "--pwfile=$passwordFile", '--encoding=UTF8', '--locale=C', '--auth-host=scram-sha-256', '--auth-local=scram-sha-256')
    $dbPort = Get-ProbePort
    Invoke-ProbePgControl @('start', '-D', $database, '-w', '-t', '30', '-l', (Join-Path $probe 'postgres.log'), '-o', "-h 127.0.0.1 -p $dbPort")
    $postgresPid = [int](Get-Content -LiteralPath (Join-Path $database 'postmaster.pid'))[0]
    $loadedCrt = @((Get-Process -Id $postgresPid).Modules | Where-Object { $_.ModuleName -ieq 'vcruntime140.dll' })
    if ($loadedCrt.Count -ne 1 -or -not $loadedCrt[0].FileName.Equals((Join-Path $pgBin 'vcruntime140.dll'), [StringComparison]::OrdinalIgnoreCase)) {
        throw '시험 PostgreSQL이 동봉된 VC++ 런타임을 사용하지 않습니다.'
    }
    Invoke-PkbCommand (Join-Path $pgBin 'createdb.exe') @('-w', '-h', '127.0.0.1', '-p', "$dbPort", '-U', 'privatekb', 'privatekb')
    $psqlArguments = @('-X', '-w', '-h', '127.0.0.1', '-p', "$dbPort", '-U', 'privatekb', '-d', 'privatekb', '-v', 'ON_ERROR_STOP=1')
    Invoke-PkbCommand (Join-Path $runtime 'ocr\tesseract.exe') @('--tessdata-dir', (Join-Path $runtime 'ocr\tessdata'), '--list-langs')

    $backendPort = Get-ProbePort
    Set-ProbeEnvironment 'PRIVATEKB_DB_URL' "jdbc:postgresql://127.0.0.1:$dbPort/privatekb"
    Set-ProbeEnvironment 'PRIVATEKB_DB_USER' 'privatekb'
    Set-ProbeEnvironment 'PRIVATEKB_DB_PASSWORD' $password
    Set-ProbeEnvironment 'PRIVATEKB_TEST_DB_URL' "jdbc:postgresql://127.0.0.1:$dbPort/privatekb"
    Set-ProbeEnvironment 'PRIVATEKB_TEST_DB_USER' 'privatekb'
    Set-ProbeEnvironment 'PRIVATEKB_TEST_DB_PASSWORD' $password
    Set-ProbeEnvironment 'PRIVATEKB_STORAGE_ROOT' (Join-Path $probe 'storage')
    Set-ProbeEnvironment 'PRIVATEKB_OCR_EXECUTABLE_DIRECTORY' (Join-Path $runtime 'ocr')
    Set-ProbeEnvironment 'PRIVATEKB_OCR_DATA_PATH' (Join-Path $runtime 'ocr\tessdata')
    Set-ProbeEnvironment 'PRIVATEKB_DESKTOP_BRIDGE_ENABLED' 'true'
    Set-ProbeEnvironment 'PRIVATEKB_DESKTOP_BRIDGE_TOKEN' $password
    Set-ProbeEnvironment 'PRIVATEKB_DESKTOP_LIFECYCLE_ENABLED' 'false'
    Push-Location $projectPath
    try {
        Invoke-PkbCommand (Join-Path $projectPath 'gradlew.bat') @('--no-daemon', 'progressiveIndexingRuntimeTest')
    } finally { Pop-Location }
    Write-Host '점진 색인 묶음 검증 통과: 임시 저장·중복 재전송·원자적 전환'
    # 빈 작업 폴더·빈 DB·별도 포트에서만 테스트한다. .env는 사용하지 않는다.
    # 사양 변경 시 Ollama 상태 조회는 가능하지만 다운로드·추론·임베딩 요청은 하지 않는다.
    $arguments = @('-jar', ('"' + $jar + '"'), "--server.port=$backendPort", '--spring.config.import=', '--privatekb.local-ai.startup-check-enabled=false')
    $backend = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $probe -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $probe 'backend.out.log') -RedirectStandardError (Join-Path $probe 'backend.err.log')
    $ready = $false
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt 45) {
        if ($backend.HasExited) { throw "검증용 Java가 종료되었습니다. 로그: $probe" }
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$backendPort/actuator/health" -TimeoutSec 1
            if ($health.status -eq 'UP') { $ready = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "독립 서비스 기동 제한 시간 초과. 로그: $probe" }
    # 확장과 테이블은 실제 앱처럼 Flyway가 처음부터 생성한 것을 검증한다.
    $result = & (Join-Path $pgBin 'psql.exe') @psqlArguments -At -c "SELECT extversion || ':' || vector_dims(array_fill(0.0, ARRAY[1024])::vector) FROM pg_extension WHERE extname = 'vector';"
    if ($LASTEXITCODE -ne 0 -or ($result | Out-String).Trim() -ne '0.8.6:1024') {
        throw '독립 PostgreSQL의 pgvector 버전·벡터 차원 검증에 실패했습니다.'
    }
    $documents = Invoke-RestMethod "http://127.0.0.1:$backendPort/api/workspaces/00000000-0000-0000-0000-000000000001/documents?size=10" -TimeoutSec 5
    if ($documents.totalElements -ne 0) { throw '검증용 DB가 빈 문서 목록을 반환하지 않았습니다.' }
    Write-Host '독립 런타임 검증 통과: 동봉 VC++·PostgreSQL·pgvector 1024차원·OCR 언어·Java·Flyway·문서 API'

    foreach ($entry in @(
        @{ Profile = 'LOW_SPEC'; Bytes = 25000000 },
        @{ Profile = 'STANDARD'; Bytes = 50000000 },
        @{ Profile = 'HIGH_SPEC'; Bytes = 100000000 },
        @{ Profile = 'DISABLED'; Bytes = 25000000 }
    )) {
        $selectionBody = @{ profile = $entry.Profile } | ConvertTo-Json -Compress
        $selectedStatus = Invoke-RestMethod "http://127.0.0.1:$backendPort/api/local-ai/chat-profile" -Method Put -ContentType 'application/json' -Body $selectionBody -TimeoutSec 5
        $uploadPolicy = Invoke-RestMethod "http://127.0.0.1:$backendPort/api/document-upload-policy" -TimeoutSec 5
        if ($uploadPolicy.maxFileBytes -ne $entry.Bytes -or $selectedStatus.chatProfile.maximumUploadBytes -ne $entry.Bytes) {
            throw "선택 사양과 실제 업로드 정책이 일치하지 않습니다: $($entry.Profile)"
        }
    }
    Write-Host '사양별 업로드 정책 검증 통과: 미사용·저사양 25MB / 일반 50MB / 고사양 100MB'

    # 원본 위치 조회도 실제 DB→세션 인증 API→네이티브 파서로 검증한다. 탐색기는 열지 않는다.
    $sourceFixture = Join-Path $probe '검증, 자료 & 회의록.txt'
    [IO.File]::WriteAllText($sourceFixture, '원본 위치 연결 시험용 합성 문서', [Text.Encoding]::UTF8)
    $sourceFile = Get-Item -LiteralPath $sourceFixture
    $sourceSqlPath = $sourceFixture.Replace("'", "''")
    $modifiedMillis = ([DateTimeOffset]$sourceFile.LastWriteTimeUtc).ToUnixTimeMilliseconds()
    $versionId = '21000000-0000-0000-0000-000000000001'
    $seedSql = @"
INSERT INTO privatekb.document(document_id, workspace_id) VALUES
('11000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001');
INSERT INTO privatekb.document_version(document_version_id, document_id, workspace_id, version_number, original_filename, declared_media_type, detected_media_type, byte_size, sha256, source_storage_key) VALUES
('$versionId', '11000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 1, 'fixture.txt', 'text/plain', 'text/plain', $($sourceFile.Length), repeat('a',64), 'smoke-fixture');
INSERT INTO privatekb.document_source_location(source_location_id, workspace_id, document_version_id, source_path, source_path_hash, byte_size, last_modified_at, status) VALUES
('31000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '$versionId', '$sourceSqlPath', repeat('b',64), $($sourceFile.Length), to_timestamp($modifiedMillis / 1000.0), 'AVAILABLE');
"@
    # Windows 명령 인자의 ANSI 변환으로 한글 경로가 손상되지 않도록 UTF-8 파일로 전달한다.
    $seedSqlFile = Join-Path $probe 'source-fixture.sql'
    [IO.File]::WriteAllText($seedSqlFile, $seedSql, (New-Object Text.UTF8Encoding($false)))
    Invoke-PkbCommand (Join-Path $pgBin 'psql.exe') ($psqlArguments + @('-f', $seedSqlFile))
    $folderIndexCount = & (Join-Path $pgBin 'psql.exe') @psqlArguments -At -c "SELECT count(*) FROM pg_indexes WHERE schemaname = 'privatekb' AND indexname IN ('idx_document_source_folder_path_search_trgm','idx_source_folder_root_path_search_trgm');"
    if ($LASTEXITCODE -ne 0 -or ($folderIndexCount | Out-String).Trim() -ne '2') {
        throw '폴더 경로 검색 인덱스 검증에 실패했습니다.'
    }
    $normalizedFolder = & (Join-Path $pgBin 'psql.exe') @psqlArguments -At -c "SELECT folder_path_search FROM privatekb.document_source_location WHERE document_version_id = '$versionId';"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($normalizedFolder | Out-String).Trim())) {
        throw '폴더 경로 검색 메타데이터 검증에 실패했습니다.'
    }
    Write-Host '폴더 경로 검색 검증 통과: 정규화 메타데이터·trigram 인덱스'
    Set-ProbeEnvironment 'PRIVATEKB_REVEAL_SMOKE_PORT' "$backendPort"
    Set-ProbeEnvironment 'PRIVATEKB_REVEAL_SMOKE_TOKEN' $password
    Set-ProbeEnvironment 'PRIVATEKB_REVEAL_SMOKE_VERSION' $versionId
    Set-ProbeEnvironment 'PRIVATEKB_REVEAL_SMOKE_FILE' $sourceFixture
    Push-Location $projectPath
    try {
        Invoke-PkbCommand 'cargo.exe' @('test', '--locked', '--manifest-path', 'frontend/src-tauri/Cargo.toml', 'document_source::tests::bridge_smoke', '--', '--ignored')
        Invoke-PkbCommand 'cargo.exe' @('test', '--locked', '--manifest-path', 'frontend/src-tauri/Cargo.toml', 'upload_transport::tests::policy_smoke', '--', '--ignored')
    } finally { Pop-Location }
    Write-Host '원본 위치 통합 검증 통과: 문서 ID 조회·세션 인증·네이티브 경로 검사·본문 미접근'
    Set-ProbeEnvironment 'PRIVATEKB_LOG_SMOKE_ROOT' (Join-Path $probe 'managed log test')
    Set-ProbeEnvironment 'PRIVATEKB_LOG_SMOKE_MANIFEST' (Join-Path $runtime 'manifest.json')
    Push-Location $projectPath
    try {
        Invoke-PkbCommand 'cargo.exe' @('test', '--locked', '--manifest-path', 'frontend/src-tauri/Cargo.toml', 'runtime::tests::managed_log_smoke', '--', '--ignored')
    } finally { Pop-Location }
    Write-Host '로그 통합 검증 통과: 실제 관리형 Java 로그 수집·PostgreSQL 크기 회전·안전 종료'
} finally {
    try {
        try {
            if ($null -ne $backend -and -not $backend.HasExited) {
                # 이 스크립트가 만든 프로세스 객체만 종료한다.
                Stop-Process -Id $backend.Id
                $backend.WaitForExit(10000) | Out-Null
            }
        } finally {
            if (Test-Path -LiteralPath (Join-Path $database 'postmaster.pid')) {
                Invoke-ProbePgControl @('stop', '-D', $database, '-m', 'fast', '-w', '-t', '30')
            }
        }
    } finally {
        foreach ($entry in $oldEnvironment.GetEnumerator()) {
            if ($null -eq $entry.Value) {
                Remove-Item -LiteralPath ("Env:" + $entry.Key) -ErrorAction SilentlyContinue
            } else {
                [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
            }
        }
        if (Test-Path -LiteralPath (Join-Path $probe 'test-password')) {
            Remove-Item -LiteralPath (Join-Path $probe 'test-password')
        }
    }
    # 실패 분석용 로그·합성 자료만 포함한 시험 DB는 build 아래에만 남긴다.
}
