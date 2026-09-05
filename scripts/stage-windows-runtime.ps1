[CmdletBinding()]
param(
    [string]$ProjectRoot = '',
    [string]$JavaHome = '',
    [string]$PostgreSqlRoot = '',
    [string]$PgvectorRoot = $env:PRIVATEKB_PGVECTOR_ROOT,
    [string]$TesseractRoot = '',
    [string]$TessdataRoot = '',
    [string]$VisualCppRuntimeRoot = '',
    [switch]$SkipBackendBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}

function Resolve-ExistingDirectory {
    param(
        [Parameter(Mandatory)]
        [string]$Path,
        [Parameter(Mandatory)]
        [string]$Description
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "$Description 디렉터리를 찾을 수 없습니다: $Path"
    }

    return (Resolve-Path -LiteralPath $Path).Path
}

function Assert-PathInside {
    param(
        [Parameter(Mandatory)]
        [string]$Parent,
        [Parameter(Mandatory)]
        [string]$Child
    )

    $parentFullPath = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\') + '\'
    $childFullPath = [System.IO.Path]::GetFullPath($Child).TrimEnd('\') + '\'
    if (-not $childFullPath.StartsWith($parentFullPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "생성 경로가 허용된 디렉터리 밖입니다: $Child"
    }
}

$projectPath = Resolve-ExistingDirectory -Path $ProjectRoot -Description '프로젝트'
. (Join-Path $PSScriptRoot 'windows-build-common.ps1')
if ([string]::IsNullOrWhiteSpace($JavaHome) -or [string]::IsNullOrWhiteSpace($PostgreSqlRoot) -or
    [string]::IsNullOrWhiteSpace($TesseractRoot) -or [string]::IsNullOrWhiteSpace($TessdataRoot)) {
    $dependencies = & (Join-Path $PSScriptRoot 'prepare-windows-dependencies.ps1') -ProjectRoot $projectPath
    if ([string]::IsNullOrWhiteSpace($JavaHome)) { $JavaHome = $dependencies.JavaHome }
    if ([string]::IsNullOrWhiteSpace($PostgreSqlRoot)) { $PostgreSqlRoot = $dependencies.PostgreSqlRoot }
    if ([string]::IsNullOrWhiteSpace($TesseractRoot)) { $TesseractRoot = $dependencies.TesseractRoot }
    if ([string]::IsNullOrWhiteSpace($TessdataRoot)) { $TessdataRoot = $dependencies.TessdataRoot }
}
$tauriPath = Join-Path $projectPath 'frontend\src-tauri'
$tauriPath = Resolve-ExistingDirectory -Path $tauriPath -Description 'Tauri 프로젝트'
$runtimePath = Join-Path $tauriPath 'runtime'
$stagePath = Join-Path $tauriPath ('.runtime-stage-' + [Guid]::NewGuid().ToString('N'))
$previousPath = Join-Path $tauriPath ('.runtime-previous-' + [Guid]::NewGuid().ToString('N'))

Assert-PathInside -Parent $tauriPath -Child $runtimePath
Assert-PathInside -Parent $tauriPath -Child $stagePath
Assert-PkbChildPath $tauriPath $runtimePath
Assert-PkbChildPath $tauriPath $stagePath
Assert-PkbChildPath $tauriPath $previousPath
if ([string]::IsNullOrWhiteSpace($VisualCppRuntimeRoot)) { $VisualCppRuntimeRoot = Get-PkbVisualCppRuntime }
foreach ($dll in @('vcruntime140.dll', 'vcruntime140_1.dll', 'msvcp140.dll')) {
    if (-not (Test-Path -LiteralPath (Join-Path $VisualCppRuntimeRoot $dll))) {
        throw "Visual C++ 런타임 파일이 없습니다: $dll"
    }
}

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $jlinkCommand = Get-Command 'jlink.exe' -ErrorAction SilentlyContinue
    if ($null -eq $jlinkCommand) {
        throw 'Java 21 JDK를 찾지 못했습니다. JAVA_HOME을 설정하거나 -JavaHome을 지정하세요.'
    }
    $JavaHome = Split-Path -Parent (Split-Path -Parent $jlinkCommand.Source)
}

$javaPath = Resolve-ExistingDirectory -Path $JavaHome -Description 'Java JDK'
$javaExecutable = Join-Path $javaPath 'bin\java.exe'
$jlinkExecutable = Join-Path $javaPath 'bin\jlink.exe'
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf) -or
    -not (Test-Path -LiteralPath $jlinkExecutable -PathType Leaf)) {
    throw "Java 실행 파일 또는 jlink를 찾지 못했습니다: $javaPath"
}

$javaReleaseFile = Join-Path $javaPath 'release'
$javaRelease = if (Test-Path -LiteralPath $javaReleaseFile -PathType Leaf) {
    Get-Content -LiteralPath $javaReleaseFile -Raw
} else {
    ''
}
if ($javaRelease -notmatch 'JAVA_VERSION="21[\.]') {
    throw "Java 21 JDK가 필요합니다. 확인한 위치: $javaPath"
}

$postgresPath = Resolve-ExistingDirectory -Path $PostgreSqlRoot -Description 'PostgreSQL'
$requiredPostgresExecutables = @('postgres.exe', 'pg_ctl.exe', 'pg_isready.exe', 'initdb.exe', 'createdb.exe', 'psql.exe')
foreach ($fileName in $requiredPostgresExecutables) {
    $candidate = Join-Path $postgresPath "bin\$fileName"
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "PostgreSQL 필수 실행 파일이 없습니다: $candidate"
    }
}

$tesseractPath = Resolve-ExistingDirectory -Path $TesseractRoot -Description 'Tesseract OCR'
$tesseractExecutable = Join-Path $tesseractPath 'tesseract.exe'
if (-not (Test-Path -LiteralPath $tesseractExecutable -PathType Leaf)) {
    throw "Tesseract 실행 파일이 없습니다: $tesseractExecutable"
}
$tesseractLibraries = @(Get-ChildItem -LiteralPath $tesseractPath -Filter '*.dll' -File)
if ($tesseractLibraries.Count -eq 0) {
    throw "Tesseract DLL 파일이 없습니다: $tesseractPath"
}
if ([string]::IsNullOrWhiteSpace($TessdataRoot)) {
    $TessdataRoot = Join-Path $projectPath 'build\ocr-tessdata'
}
$tessdataPath = Resolve-ExistingDirectory -Path $TessdataRoot -Description 'OCR 언어 모델'
foreach ($modelName in @('kor.traineddata', 'eng.traineddata')) {
    $modelPath = Join-Path $tessdataPath $modelName
    if (-not (Test-Path -LiteralPath $modelPath -PathType Leaf)) {
        throw "OCR 언어 모델이 없습니다: $modelPath"
    }
}

$preparedPgvectorPath = Join-Path $projectPath 'build\pgvector-windows'
$vectorSourcePath = if (-not [string]::IsNullOrWhiteSpace($PgvectorRoot)) {
    Resolve-ExistingDirectory -Path $PgvectorRoot -Description 'pgvector'
} elseif (Test-Path -LiteralPath $preparedPgvectorPath -PathType Container) {
    (Resolve-Path -LiteralPath $preparedPgvectorPath).Path
} else {
    $postgresPath
}

$vectorControl = Join-Path $vectorSourcePath 'share\extension\vector.control'
$vectorLibrary = Join-Path $vectorSourcePath 'lib\vector.dll'
$vectorSqlFiles = @(Get-ChildItem -LiteralPath (Join-Path $vectorSourcePath 'share\extension') -Filter 'vector--*.sql' -File -ErrorAction SilentlyContinue)
if (-not (Test-Path -LiteralPath $vectorControl -PathType Leaf) -or
    -not (Test-Path -LiteralPath $vectorLibrary -PathType Leaf) -or
    $vectorSqlFiles.Count -eq 0) {
    throw @"
Windows용 pgvector 확장 파일이 완전하지 않습니다.
필수 파일: share\extension\vector.control, share\extension\vector--*.sql, lib\vector.dll
PostgreSQL에 확장을 설치한 뒤 -PgvectorRoot로 해당 루트 디렉터리를 지정하세요.
확인한 위치: $vectorSourcePath
"@
}

$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $javaPath
    New-Item -ItemType Directory -Path $stagePath | Out-Null
    $gradleWrapper = Join-Path $projectPath 'gradlew.bat'

    if (-not $SkipBackendBuild) {
        Push-Location $projectPath
        try {
            & $gradleWrapper --no-daemon bootJar
            if ($LASTEXITCODE -ne 0) {
                throw "Spring Boot 실행 JAR 빌드에 실패했습니다. 종료 코드: $LASTEXITCODE"
            }
        } finally {
            Pop-Location
        }
    }

    $jarCandidates = @(Get-ChildItem -LiteralPath (Join-Path $projectPath 'build\libs') -Filter '*.jar' -File |
        Where-Object { $_.Name -notlike '*-plain.jar' } |
        Sort-Object LastWriteTime -Descending)
    if ($jarCandidates.Count -eq 0) {
        throw 'Spring Boot 실행 JAR를 찾지 못했습니다. bootJar를 먼저 실행하세요.'
    }

    $javaRuntimePath = Join-Path $stagePath 'java'
    $jlinkArguments = @(
        '--add-modules', 'java.se,jdk.charsets,jdk.crypto.ec,jdk.management,jdk.unsupported,jdk.zipfs',
        '--strip-debug',
        '--no-header-files',
        '--no-man-pages',
        '--compress=zip-6',
        '--output', $javaRuntimePath
    )
    & $jlinkExecutable @jlinkArguments
    if ($LASTEXITCODE -ne 0) {
        throw "전용 Java 런타임 생성에 실패했습니다. 종료 코드: $LASTEXITCODE"
    }
    if (Test-Path -LiteralPath (Join-Path $javaPath 'NOTICE')) {
        Copy-Item -LiteralPath (Join-Path $javaPath 'NOTICE') -Destination $javaRuntimePath
    }

    # 개발 JDK가 아닌 실제 배포용 Java에서 구형 Office 문자셋과 파싱을 검증한다.
    $previousTestJava = $env:PRIVATEKB_TEST_JAVA
    Push-Location $projectPath
    try {
        $env:PRIVATEKB_TEST_JAVA = Join-Path $javaRuntimePath 'bin\java.exe'
        & $gradleWrapper --no-daemon packagedRuntimeTest
        if ($LASTEXITCODE -ne 0) {
            throw '배포용 Java의 Office 파싱 검증에 실패했습니다. 기존 런타임은 유지합니다.'
        }
    } finally {
        $env:PRIVATEKB_TEST_JAVA = $previousTestJava
        Pop-Location
    }

    $appPath = Join-Path $stagePath 'app'
    New-Item -ItemType Directory -Path $appPath | Out-Null
    Copy-Item -LiteralPath $jarCandidates[0].FullName -Destination (Join-Path $appPath 'privatekb.jar')

    $stagedPostgresPath = Join-Path $stagePath 'postgresql'
    New-Item -ItemType Directory -Path $stagedPostgresPath | Out-Null
    foreach ($directoryName in @('bin', 'lib', 'share')) {
        Copy-Item -LiteralPath (Join-Path $postgresPath $directoryName) -Destination $stagedPostgresPath -Recurse
    }
    # EDB ZIP은 VC++ CRT를 포함하지 않는다. 기존 PC의 System32에 기대지 않는다.
    $crtFiles = @(Get-ChildItem -LiteralPath $VisualCppRuntimeRoot -Filter '*.dll' -File)
    $crtFiles | Copy-Item -Destination (Join-Path $stagedPostgresPath 'bin')
    $crtFiles | ForEach-Object {
        [ordered]@{ file = $_.Name; version = $_.VersionInfo.FileVersion; sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash }
    } | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $stagePath 'visual-cpp-runtime.json') -Encoding UTF8

    $licenseCandidates = @('server_license.txt', 'commandlinetools_3rd_party_licenses.txt', 'COPYRIGHT')
    foreach ($licenseName in $licenseCandidates) {
        $licensePath = Join-Path $postgresPath $licenseName
        if (Test-Path -LiteralPath $licensePath -PathType Leaf) {
            Copy-Item -LiteralPath $licensePath -Destination $stagedPostgresPath
        }
    }

    if (-not $vectorSourcePath.Equals($postgresPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        Copy-Item -LiteralPath $vectorControl -Destination (Join-Path $stagedPostgresPath 'share\extension') -Force
        foreach ($sqlFile in $vectorSqlFiles) {
            Copy-Item -LiteralPath $sqlFile.FullName -Destination (Join-Path $stagedPostgresPath 'share\extension') -Force
        }
        Copy-Item -LiteralPath $vectorLibrary -Destination (Join-Path $stagedPostgresPath 'lib') -Force
        $pgvectorLicense = Join-Path $vectorSourcePath 'LICENSE'
        if (Test-Path -LiteralPath $pgvectorLicense -PathType Leaf) {
            Copy-Item -LiteralPath $pgvectorLicense -Destination (Join-Path $stagedPostgresPath 'pgvector-LICENSE')
        }
    }

    $stagedOcrPath = Join-Path $stagePath 'ocr'
    $stagedTessdataPath = Join-Path $stagedOcrPath 'tessdata'
    New-Item -ItemType Directory -Path $stagedTessdataPath -Force | Out-Null
    Copy-Item -LiteralPath $tesseractExecutable -Destination $stagedOcrPath
    foreach ($library in $tesseractLibraries) {
        Copy-Item -LiteralPath $library.FullName -Destination $stagedOcrPath
    }
    foreach ($modelName in @('kor.traineddata', 'eng.traineddata')) {
        Copy-Item -LiteralPath (Join-Path $tessdataPath $modelName) -Destination $stagedTessdataPath
    }
    foreach ($licenseName in @('tesseract-LICENSE', 'tessdata-fast-LICENSE')) {
        $licensePath = Join-Path $tessdataPath $licenseName
        if (Test-Path -LiteralPath $licensePath -PathType Leaf) {
            Copy-Item -LiteralPath $licensePath -Destination $stagedOcrPath
        }
    }

    $stagedRequiredFiles = @(
        'java\bin\java.exe',
        'app\privatekb.jar',
        'postgresql\bin\postgres.exe',
        'postgresql\bin\pg_ctl.exe',
        'postgresql\bin\initdb.exe',
        'postgresql\bin\createdb.exe',
        'postgresql\bin\vcruntime140.dll',
        'postgresql\bin\vcruntime140_1.dll',
        'postgresql\bin\msvcp140.dll',
        'postgresql\share\extension\vector.control',
        'postgresql\lib\vector.dll',
        'ocr\tesseract.exe',
        'ocr\tessdata\kor.traineddata',
        'ocr\tessdata\eng.traineddata'
    )
    foreach ($relativePath in $stagedRequiredFiles) {
        $candidate = Join-Path $stagePath $relativePath
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            throw "런타임 배치 검증에 실패했습니다: $relativePath"
        }
    }

    $stagedVectorSqlFiles = @(Get-ChildItem -LiteralPath (Join-Path $stagePath 'postgresql\share\extension') -Filter 'vector--*.sql' -File)
    if ($stagedVectorSqlFiles.Count -eq 0) {
        throw '배치된 런타임에 pgvector SQL 파일이 없습니다.'
    }

    $manifest = [ordered]@{
        schemaVersion = 2
        runtimeVersion = '2026.08.31-portable2'
        java = [ordered]@{
            executable = 'java\bin\java.exe'
        }
        backend = [ordered]@{
            jar = 'app\privatekb.jar'
            port = 8080
        }
        database = [ordered]@{
            root = 'postgresql'
            port = 5433
            name = 'privatekb'
            user = 'privatekb'
        }
        ocr = [ordered]@{
            executableDirectory = 'ocr'
            dataPath = 'ocr\tessdata'
        }
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $stagePath 'manifest.json') -Encoding utf8
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'windows-dependencies.lock.json') -Destination $stagePath

    $runtimeReadme = Join-Path $runtimePath 'README.md'
    if (Test-Path -LiteralPath $runtimeReadme -PathType Leaf) {
        Copy-Item -LiteralPath $runtimeReadme -Destination (Join-Path $stagePath 'README.md')
    }

    if (Test-Path -LiteralPath $runtimePath) {
        Move-Item -LiteralPath $runtimePath -Destination $previousPath
    }
    try {
        Move-Item -LiteralPath $stagePath -Destination $runtimePath
    } catch {
        if (Test-Path -LiteralPath $previousPath) {
            Move-Item -LiteralPath $previousPath -Destination $runtimePath
        }
        throw
    }
    Remove-PkbGeneratedPath $tauriPath $previousPath

    $runtimeSize = (Get-ChildItem -LiteralPath $runtimePath -Recurse -File | Measure-Object Length -Sum).Sum
    Write-Host "Windows 전용 런타임 배치가 완료되었습니다."
    Write-Host "위치: $runtimePath"
    Write-Host ("크기: {0:N1} MiB" -f ($runtimeSize / 1MB))
} catch {
    if (Test-Path -LiteralPath $stagePath) {
        Assert-PathInside -Parent $tauriPath -Child $stagePath
        Remove-PkbGeneratedPath $tauriPath $stagePath
    }
    throw
} finally {
    $env:JAVA_HOME = $previousJavaHome
}
