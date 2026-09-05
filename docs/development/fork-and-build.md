# Fork 후 실행·Windows 설치 파일 만들기

## 가장 간단한 방법: GitHub에서 설치 파일 생성

개발 도구를 내 PC에 설치하지 않고도 fork한 저장소의 GitHub Actions에서 설치 파일을 만들 수 있다.
**Fork만으로 빌드가 자동 실행되지는 않는다.** Actions를 활성화하고 한 번 실행해야 한다.

1. 이 저장소를 자신의 GitHub 계정으로 fork한다.
2. fork의 **Actions**에서 워크플로 사용을 활성화한다.
3. **Windows 설치 파일 만들기** → **Run workflow**를 누른다.
4. 성공한 실행의 **Artifacts → PrivateKB-windows-x64**를 내려받아 압축을 푼다.
5. `PrivateKB_<버전>_x64-setup.exe`를 실행해 설치하고 시작 메뉴에서 PrivateKB를 실행한다.

워크플로 파일이 기본 브랜치에 있어야 수동 실행 메뉴를 사용할 수 있다.
GitHub 권한·조직 정책·Actions 할당량에 따라 실행이 제한되거나 비용이 발생할 수 있다.
생성물은 14일 보관하며 저장소나 Releases에 자동 공개하지 않는다.
메뉴 동작은 [GitHub 수동 실행 안내](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)를 참고한다.

## 설치본을 사용하는 사람

- Windows 10/11 **x64**. ARM64 전용 빌드는 아직 제공하지 않는다.
- Ollama와 AI 모델은 별도 설치한다. Ollama가 없어도 문서 관리·파싱은 사용 가능하다.
- 의미 검색에는 `qwen3-embedding:0.6b`, AI 답변에는 설정에서 선택한 대화 모델이 필요하다.
- **Docker, PostgreSQL, Java, Node.js, Rust, Tesseract를 별도로 설치할 필요가 없다.**
- WebView2가 없는 PC는 Tauri 설치 과정에서 다운로드가 필요할 수 있다.

설치 파일에는 전용 Java 런타임, Spring Boot, PostgreSQL, pgvector, OCR와 한국어·영어 OCR 모델이 들어간다.
Ollama·임베딩 모델·대화 모델, 업로더의 문서·DB·암호는 포함하지 않는다.
서명되지 않은 개발 검증용 설치본이므로 Windows 경고가 나올 수 있다.

## 내 PC에서 수정하고 설치 파일 만들기

먼저 다음 개발 도구만 준비한다.

| 개발 도구 | 기준 |
| --- | --- |
| Git | 저장소 내려받기 |
| Node.js | 24 LTS, CI 기준 `.node-version` |
| Rustup | `rust-toolchain.toml`의 Rust 1.98.0, MSVC x64 |
| Visual Studio C++ Build Tools | 데스크톱 C++ 워크로드, x64 MSVC·CRT 14.44 이상, Windows SDK |
| PowerShell | Windows 기본 5.1 또는 PowerShell 7 |

JDK·PostgreSQL·pgvector 소스·OCR·압축 해제 도구는 스크립트가 프로젝트의 `.build-deps`에 준비한다.
Windows 서비스, 전역 PATH, 기존 PostgreSQL 설치·계정·데이터는 변경하지 않는다.
빌드 준비에는 인터넷 연결과 다운로드·컴파일용 여유 공간이 필요하다. 10GB 이상의 여유 공간을 권장한다.
C++ 도구 설치 후 새 PowerShell 창에서 실행한다.
[Tauri 공식 개발 환경 안내](https://v2.tauri.app/start/prerequisites/)도 참고한다.

저장소 루트에서:

```powershell
# Node.js, Rustup, C++ 도구의 기본 설치 상태 확인
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 -Mode Check

# 의존성 준비 → 테스트 → Windows 설치 파일 생성
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1
```

결과 위치:

```text
frontend/src-tauri/target/release/bundle/nsis/
  PrivateKB_<버전>_x64-setup.exe
  PrivateKB_<버전>_x64-setup.exe.sha256
  PrivateKB_<버전>_x64-setup.exe.build.json
```

기존 `frontend`의 `npm run desktop:bundle`도 같은 공통 스크립트를 실행한다.
빌드가 끝나도 현재 PC의 설치본을 자동 교체하거나 앱을 실행하지 않는다.
컴파일 경로에는 `%`, `&`, `!`, `^`, 큰따옴표를 사용하지 않는다.

## 고정 버전과 검증

`scripts/windows-dependencies.lock.json`이 다운로드 URL, 버전·커밋, SHA-256을 고정한다.
JDK는 Temurin 21.0.12.1+1, PostgreSQL은 기존 검증 기준인 18.4, pgvector는 0.8.6,
OCR는 기존 검증 기준인 Tesseract 5.4.0.20240606과 `tessdata_fast`다.
이 목록을 모든 구성 요소의 최신 보안 버전이라는 의미로 해석하지 않는다.
배포 전 버전·보안 공지와 재배포 조건을 점검하고 고정 파일을 갱신한 뒤 다시 시험한다.

- 다운로드는 HTTPS로 받고 SHA-256 확인 후 사용한다. 불일치 시 중단한다.
- 중단된 다운로드는 완성된 캐시로 취급하지 않는다.
- 준비된 파일도 해시를 다시 확인한다. 손상된 캐시는 자동 신뢰하지 않는다.
- PostgreSQL ZIP에서 `bin`, `lib`, `share`, `include`와 라이선스만 선택한다. pgAdmin·StackBuilder는 포함하지 않는다.
- PostgreSQL 실행에 필요한 VC++ CRT는 C++ Build Tools의 x64 재배포 폴더에서 자동 탐색하여 실행 파일 옆에 동봉한다. 파일 버전·해시는 `runtime/visual-cpp-runtime.json`에 기록하고, 시험 프로세스가 실제로 이 DLL을 불러오는지 확인한다.
- Tesseract와 7-Zip 설치 EXE는 실행하지 않고 필요한 파일만 압축 해제한다.
- pgvector는 고정 커밋 소스를 새 작업 폴더에 복사하여 빌드한다. 기존 DLL을 재사용하지 않는다.
- npm은 `npm ci`, Cargo는 `--locked`, Gradle Wrapper는 배포 ZIP 체크섬을 사용한다.
- 패키징에서는 `--no-daemon`으로 Gradle 상주 프로세스를 남기지 않는다.
- C++ 컴파일러·Windows SDK 등 차이 때문에 설치 EXE의 바이트까지 항상 동일하다는 보장은 아니다.

다음 명령은 **런타임 다운로드 캐시만** 오프라인으로 검사한다.
전체 npm·Gradle·Cargo·NSIS 의존성의 오프라인 빌드를 보장하는 옵션은 아니다.

```powershell
.\scripts\prepare-windows-dependencies.ps1 -Offline
```

고정 파일이나 준비 로직을 변경했다면 `.build-deps`의 해당 생성 캐시를 확인 후 다시 준비한다.
SHA-256 오류를 무시하거나 검증 코드를 제거하지 않는다.

주요 원본:
[PostgreSQL Windows 배포](https://www.postgresql.org/download/windows/),
[EDB ZIP 배포](https://www.enterprisedb.com/download-postgresql-binaries),
[Temurin](https://adoptium.net/temurin/releases/),
[pgvector](https://github.com/pgvector/pgvector),
[Tesseract Windows 배포](https://github.com/UB-Mannheim/tesseract/wiki),
[OCR 언어 데이터](https://github.com/tesseract-ocr/tessdata_fast),
[7-Zip](https://www.7-zip.org/download.html).

## 검증 범위

실행별 검증 결과는 로컬의 `build/reports/tests/` 또는 GitHub Actions의 테스트 보고서 아티팩트에서 확인한다.

공통 패키징 명령은 Java 단위 테스트, 배포 Java Office 파싱, 프런트엔드 테스트,
Rust 테스트, 빌드 스크립트 검증을 실행한다.
`build/runtime-smoke-*` 아래에 새 시험 DB를 만들고 임시 루프백 포트에서 pgvector 1024차원,
OCR 언어 목록, Java·Flyway 기동과 빈 문서 목록 API를 확인한 뒤 종료한다.
기존 설치본의 데이터 폴더나 DB 포트로 연결하지 않는다.

Docker 기반 `integrationTest`와 실제 Windows 설치·삭제·업데이트 인수 시험은 별도다.
테스트 통과가 서명·권한·배포 보안 검토까지 완료됐다는 뜻은 아니다.

## 개발용 화면을 따로 실행하는 경우

기존 Docker Compose 개발 방식도 유지한다. 이 경우에만 Docker가 필요하다.
`.env.example`을 `.env`로 복사하고 충분히 긴 개발용 암호를 지정한다.
이미 `.env`가 있으면 덮어쓰지 않는다. 설치본의 비밀번호 파일을 복사하지 않는다.
개발 DB와 설치본의 DB가 같은 포트를 쓰지 않도록 `.env`의 포트를 구분한다.
루트 README의 개발 실행 절차를 참고한다.

## Git에 올리기 전

- `.env`, `.build-deps`, `build`, `node_modules`, `target`, 생성된 `runtime`은 제외한다.
- 사용자 원문·추출문·벡터·DB·암호·로그·설치본을 소스 커밋에 포함하지 않는다.
- `package-lock.json`, `Cargo.lock`, Gradle Wrapper, 의존성 고정 파일과 워크플로는 포함한다.
- 데이터 제외 규칙은 루트 경로에 한정한다. Java의 `internal/storage` 소스와 테스트가 제외되지 않는지 빌드 검증에서 확인한다.
- 기획서·개발 보고서·UI 시안·수동 테스트 자료는 제외하며 이 빌드 안내만 공개한다.
- 자동화 테스트 소스는 포함한다. 테스트에 필요한 PDF는 실행 중 생성한다.
- 프로젝트 자체의 공개 라이선스는 소유자가 결정해야 한다. 이번 변경에서 임의로 부여하지 않았다.
- 제3자 라이선스 파일 포함은 전체 재배포 의무 검토를 대신하지 않는다. 공개 배포 전 별도로 점검한다.
- VC++ 파일의 재배포 권한·조건과 앱에 동봉한 DLL의 보안 업데이트 책임은 [Microsoft 재배포 안내](https://learn.microsoft.com/en-us/cpp/windows/redistributing-visual-cpp-files?view=msvc-170)에 따라 확인한다. 시스템 공용 런타임을 설치·변경하지 않는 대신, 동봉 DLL 업데이트는 PrivateKB 패키지를 다시 배포해야 한다.
- 저장소 push, Releases 공개, 코드 서명, 사용자 PC 설치는 자동 수행하지 않는다.
