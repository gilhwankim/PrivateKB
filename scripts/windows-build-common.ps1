Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-PkbChildPath {
    param([string]$Parent, [string]$Child)
    $parentPath = [IO.Path]::GetFullPath($Parent).TrimEnd('\')
    $childPath = [IO.Path]::GetFullPath($Child).TrimEnd('\')
    if (-not $childPath.StartsWith($parentPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "허용된 작업 폴더 밖이거나 작업 폴더 자체인 경로입니다: $Child"
    }
    # 기존 정션·심볼릭 링크를 따라가서 다른 폴더를 수정하지 않는다.
    $cursor = $childPath
    while ($cursor.Length -ge $parentPath.Length) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "링크 경로는 빌드 출력에 사용할 수 없습니다: $cursor"
            }
        }
        $cursor = Split-Path -Parent $cursor
    }
}

function Remove-PkbGeneratedPath {
    param([string]$Parent, [string]$Path)
    Assert-PkbChildPath $Parent $Path
    if (Test-Path -LiteralPath $Path) {
        # 하위 링크도 검사한 후 PowerShell에서만 삭제한다.
        $links = @(Get-ChildItem -LiteralPath $Path -Recurse -Force |
            Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint })
        if ($links.Count -gt 0) { throw "생성 폴더 내부에 링크가 있습니다: $Path" }
        Remove-Item -LiteralPath $Path -Recurse -Force
    }
}

function Invoke-PkbCommand {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "명령 실행 실패: $Executable (종료 코드 $LASTEXITCODE)"
    }
}

function Assert-PkbHash {
    param([string]$Path, [string]$Expected)
    if ($Expected -notmatch '^[0-9a-fA-F]{64}$') { throw 'SHA-256 고정값이 올바르지 않습니다.' }
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Expected) {
        throw "SHA-256 불일치: $Path. 파일을 사용하지 않습니다. 캐시를 확인한 뒤 다시 준비하세요."
    }
}

function Get-PkbDownload {
    param($Artifact, [string]$Cache, [switch]$Offline)
    if ([IO.Path]::GetFileName($Artifact.file) -ne $Artifact.file -or $Artifact.file.Contains(':')) {
        throw '다운로드 파일명에 경로를 넣을 수 없습니다.'
    }
    $uri = [Uri]$Artifact.url
    if ($uri.Scheme -ne 'https') { throw '다운로드는 HTTPS만 허용합니다.' }
    $path = Join-Path $Cache $Artifact.file
    Assert-PkbChildPath $Cache $path
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        Assert-PkbHash $path $Artifact.sha256
        return $path
    }
    if ($Offline) { throw "오프라인 캐시가 없습니다: $($Artifact.file)" }
    New-Item -ItemType Directory -Path $Cache -Force | Out-Null
    $partial = $path + '.' + [Guid]::NewGuid().ToString('N') + '.partial'
    $oldProgress = $ProgressPreference
    $oldProtocol = [Net.ServicePointManager]::SecurityProtocol
    try {
        $ProgressPreference = 'SilentlyContinue'
        [Net.ServicePointManager]::SecurityProtocol = $oldProtocol -bor [Net.SecurityProtocolType]::Tls12
        Write-Host "다운로드: $($Artifact.file)"
        Invoke-WebRequest -UseBasicParsing -UserAgent 'PrivateKB-build/0.1' -Uri $uri -OutFile $partial -TimeoutSec 600
        Assert-PkbHash $partial $Artifact.sha256
        Move-Item -LiteralPath $partial -Destination $path
    } finally {
        $ProgressPreference = $oldProgress
        [Net.ServicePointManager]::SecurityProtocol = $oldProtocol
        if (Test-Path -LiteralPath $partial) {
            Assert-PkbChildPath $Cache $partial
            Remove-Item -LiteralPath $partial -Force
        }
    }
    return $path
}

function Expand-PkbZip {
    param([string]$Zip, [string]$Destination, [string]$SevenZip, [string[]]$Includes = @())
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Zip)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName.Contains(':') -or [IO.Path]::IsPathRooted($entry.FullName)) {
                throw "잘못된 압축 경로입니다: $($entry.FullName)"
            }
            Assert-PkbChildPath $Destination (Join-Path $Destination $entry.FullName)
            # UNIX 심볼릭 링크 항목을 거부한다.
            if ((($entry.ExternalAttributes -shr 16) -band 0xF000) -eq 0xA000) {
                throw "압축 파일의 심볼릭 링크는 허용하지 않습니다: $($entry.FullName)"
            }
        }
    } finally { $archive.Dispose() }
    Invoke-PkbCommand $SevenZip (@('x', $Zip, "-o$Destination", '-y', '-bso0', '-bsp0') + $Includes)
}

function Get-PkbVsDevCmd {
    param([string]$BuildToolsRoot = '')
    if ([string]::IsNullOrWhiteSpace($BuildToolsRoot)) {
        $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
        if (-not (Test-Path -LiteralPath $vswhere)) {
            throw 'Visual Studio C++ Build Tools의 데스크톱 C++ 워크로드와 Windows SDK를 설치하세요.'
        }
        $BuildToolsRoot = (& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath | Out-String).Trim()
    }
    if ([string]::IsNullOrWhiteSpace($BuildToolsRoot)) { throw 'MSVC x64 C++ 도구를 찾지 못했습니다.' }
    $command = Join-Path $BuildToolsRoot 'Common7\Tools\VsDevCmd.bat'
    if (-not (Test-Path -LiteralPath $command)) { throw "C++ 빌드 환경이 없습니다: $command" }
    return $command
}

function Get-PkbVisualCppRuntime {
    $vsRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Get-PkbVsDevCmd)))
    $redistRoot = Join-Path $vsRoot 'VC\Redist\MSVC'
    $versions = @(Get-ChildItem -LiteralPath $redistRoot -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending)
    foreach ($version in $versions) {
        $x64 = Join-Path $version.FullName 'x64'
        if (-not (Test-Path -LiteralPath $x64)) { continue }
        $crt = @(Get-ChildItem -LiteralPath $x64 -Directory -Filter 'Microsoft.VC*.CRT' |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'vcruntime140.dll') })
        if ($crt.Count -gt 0) {
            $runtimeVersion = (Get-Item -LiteralPath (Join-Path $crt[0].FullName 'vcruntime140.dll')).VersionInfo
            if ($runtimeVersion.FileMajorPart -gt 14 -or ($runtimeVersion.FileMajorPart -eq 14 -and $runtimeVersion.FileMinorPart -ge 44)) {
                return $crt[0].FullName
            }
        }
    }
    throw 'MSVC 14.44 이상 x64 CRT가 필요합니다. 최신 Visual Studio C++ Build Tools의 MSVC 도구를 확인하세요.'
}
