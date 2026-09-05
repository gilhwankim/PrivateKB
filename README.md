# PrivateKB

PrivateKB는 민감한 PDF, Word, PowerPoint, Excel, HWP 5.x, Markdown, TXT 문서를 위한 개인정보 보호형 로컬 지식 관리 서비스다. MVP는 현재 사용자가 열람할 권한이 있는 내용만 검색하며, 같은 PC에서 실행되는 Ollama를 이용해 추적 가능한 근거와 함께 답변한다.

현재 저장소에는 **1~4주차 개발 단위와 5주차 Windows 자체 실행·문서 연결 기반**이 포함되어 있다. 프로젝트 기반과 보안 명세, 문서 업로드·파싱, Ollama 준비 상태 확인, 문서 청킹, pgvector 혼합 검색, 로컬 대화 모델의 근거 기반 스트리밍 답변, Tauri Windows 실행 창, 네이티브 파일·폴더 수집과 NSIS 설치 프로그램을 제공한다.

브라우저 주소를 직접 여는 방식이 아니라 `PrivateKB.exe`로 실행하는 Windows 데스크톱 애플리케이션이다. 현재 설치 프로그램은 전용 Java 21 런타임, Spring Boot, PostgreSQL 18과 pgvector 0.8.6을 함께 설치하고 앱 실행·종료에 맞춰 로컬 서비스를 관리한다. Ollama와 AI 모델은 설치 프로그램에 포함하지 않는다. 현재 산출물은 코드 서명과 독립 Windows 인수 시험 전의 개발 검증용 설치본이다.

## Fork 후 설치 파일 만들기

다른 PC에 PostgreSQL·Java·OCR를 설치하거나 개인 PC 경로를 수정할 필요 없이 패키징할 수 있다.

- 개발 도구 없이: fork → Actions 활성화 → **Windows 설치 파일 만들기** 실행 → Artifacts에서 setup 다운로드.
- 로컬에서 수정·빌드: Node.js 24, Rustup, C++ Build Tools를 준비한 뒤 아래 명령 실행.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1
```

필요한 JDK·PostgreSQL·OCR는 프로젝트 전용으로 다운로드하고 검증한다. Docker는 패키징에 필요하지 않다.
Fork 자체가 빌드를 실행하는 것은 아니며, 설치본 사용자는 AI 기능을 위해 Ollama와 모델을 별도로 준비한다.
[전체 절차와 주의 사항](docs/development/fork-and-build.md)을 참고한다.

## 로컬 모델 설정

| 용도 | 모델 또는 값 |
| --- | --- |
| 대화 미사용 | 대화 모델을 실행하지 않고 문서 관리·의미 검색만 사용, 파일당 최대 25MB |
| 대화 저사양 | `qwen3.5:2b-q4_K_M`, 문맥 4096, 최대 출력 512, 근거 문서 3개, 파일당 최대 25MB |
| 대화 일반 | `qwen3.5:4b`, 문맥 8192, 최대 출력 1024, 근거 문서 5개, 파일당 최대 50MB |
| 대화 고사양 | `qwen3.5:9b`, 문맥 8192, 최대 출력 1536, 근거 문서 7개, 파일당 최대 100MB |
| 임베딩 | `qwen3-embedding:0.6b` |
| 임베딩 차원 | 1024 |
| Ollama 주소 | `http://127.0.0.1:11434` 또는 `http://localhost:11434`만 허용 |

애플리케이션은 시작할 때 루프백이 아닌 AI 주소를 거부한다. 클라우드 모델, 웹 검색, 원격 텔레메트리 전송기와 외부 연결 기능은 포함하지 않는다.

데스크톱 GUI는 실행할 때 Ollama, 임베딩 모델과 사용자가 선택한 대화 프로필 상태를 자동 확인하고, 화면 우측 상단의 `AI 상태 다시 확인` 버튼으로 수동 점검할 수 있게 한다. Ollama가 없어도 문서 관리와 파싱은 사용할 수 있고, 임베딩이 준비되면 의미 검색, 임베딩과 선택한 대화 모델이 모두 준비되면 근거 기반 답변을 활성화한다. 대화 프로필을 바꿔도 임베딩 모델과 기존 벡터는 바뀌지 않는다.

Ollama는 사용자가 별도로 설치하고 실행한다. 설정 화면에서 `미사용`, `저사양`, `일반`, `고사양` 중 하나를 고르며 최초 기본값은 `미사용`이다. 프로필 선택만으로 다운로드를 시작하지 않는다. 선택 모델이 없으면 AI 상태 창과 설정 화면에 설치 버튼이 나타나고, 예상 용량, 외부 다운로드와 Ollama 저장 위치 사용 안내를 확인하고 명시적으로 승인해야만 PrivateKB가 루프백 Ollama의 모델 가져오기 API를 호출한다. 운영체제 명령 셸을 실행하거나 사용자가 입력한 모델명·URL을 사용하지 않으며, 다른 설치 모델로 자동 전환하거나 기존 Ollama 모델을 자동 삭제하지 않는다.

사양 선택은 새로 추가하는 문서의 파일당 제한에도 적용한다. `미사용`과 `저사양`은 25MB, `일반`은 50MB, `고사양`은 100MB이며 MB는 1,000,000바이트 기준이다. 설정을 낮춰도 이미 처리한 문서나 벡터를 삭제하거나 다시 임베딩하지 않는다. OCR 20쪽, 추출문 500만 자와 그 밖의 형식·일괄 수집 안전 제한은 별도로 유지한다.

## 설치본 사용 준비 사항

- Windows 10 또는 11
- 사용자가 별도로 설치하고 실행하는 Ollama
- 사용할 임베딩 모델과 대화 모델에 필요한 디스크 공간

설치본 사용자는 Java, PostgreSQL, pgvector와 Docker를 별도로 설치하지 않는다. GPU가 없어도 Ollama의 CPU 실행은 가능하지만 응답 시간이 길어질 수 있다.

## 개발 환경 준비 사항

- 백엔드를 개별 개발 실행할 때 JDK 21 (설치 파일 빌드 명령은 전용 Temurin JDK를 준비)
- Docker Desktop과 Compose v2 (기존 개별 개발 실행·컨테이너 통합 테스트에만 필요)
- Node.js 24, Rustup와 Tauri 빌드 도구
- 설치 파일 생성 시 Visual Studio C++ Build Tools와 Windows SDK (PostgreSQL 개발 헤더는 자동 준비)
- 루프백에만 바인딩하고 `OLLAMA_NO_CLOUD=1`을 적용한 Ollama
- GPU를 사용할 경우 해당 그래픽 드라이버

개발 도구의 준비 상태는 `scripts/build-windows.ps1 -Mode Check`로 확인한다.

## 데이터베이스 시작

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# .env를 열고 개발용 암호를 변경한다.
docker compose up -d database
docker compose ps
```

현재 PC에 설치된 PostgreSQL의 기본 포트와 충돌하지 않도록 개발용 컨테이너는 기본적으로 `127.0.0.1:5433`에만 바인딩된다. 포트는 `.env`의 `PRIVATEKB_DB_PORT`로 변경할 수 있다. 컨테이너는 PrivateKB 전용 Docker 브리지 네트워크에 배치되며 다른 PC에는 포트를 공개하지 않는다. `docker compose` 명령을 사용할 수 없는 환경에서는 같은 인자를 `docker-compose`에 전달한다.

## 빌드와 테스트

```powershell
.\gradlew.bat test
.\gradlew.bat integrationTest
.\gradlew.bat build
```

`test`는 Docker 없이 로컬 AI 외부 전송 차단 정책, 문서 형식·파서 계약, 스트리밍 청크·OCR 예산과 Spring Modulith 경계를 검사한다. `integrationTest`는 고정된 pgvector 이미지를 시작하고 Flyway를 실행한 뒤 확장 버전, 문서 수집·색인 흐름과 1024차원 계약을 검증한다. Docker를 사용할 수 없을 때 데이터베이스 검증이 통과한 것처럼 보이지 않도록 명시적으로 실패한다. Windows 런타임 검증은 동봉 PostgreSQL·pgvector의 빈 DB에서 `progressiveIndexingRuntimeTest`를 실행해 64청크 JDBC 묶음, 중복 재전송, 최종 원자적 전환·중단 복구·72시간 정리를 확인한다.

## 로컬 실행

```powershell
$env:OLLAMA_NO_CLOUD = '1'
$env:OLLAMA_CONTEXT_LENGTH = '8192'
$env:OLLAMA_MAX_LOADED_MODELS = '1'
$env:OLLAMA_NUM_PARALLEL = '1'
.\gradlew.bat bootRun
```

Spring Boot는 프로젝트 루트의 `.env`를 로컬 설정 파일로 불러온다. 애플리케이션 상태 확인 주소는 `http://127.0.0.1:8080/actuator/health`이며 상세 정보는 외부에 표시하지 않는다.

Ollama와 모델 준비 상태는 다음 API에서 확인하거나 다시 점검할 수 있다.

```powershell
curl.exe "http://127.0.0.1:8080/api/local-ai/status"
curl.exe -X POST "http://127.0.0.1:8080/api/local-ai/checks"
```

수동 점검 요청은 HTTP 202로 즉시 반환되며, 점검 중에는 `checkInProgress`가 `true`다. 완료 상태는 GET API로 다시 확인한다. 이 점검은 문서 본문이나 질문을 Ollama에 보내지 않고 버전과 설치 모델 메타데이터만 확인한다.

누락 모델 설치는 GUI 사용을 권장한다. API 계약을 시험할 때는 역할별 고정 주소와 명시적 확인 값만 사용한다.

```powershell
curl.exe -X POST "http://127.0.0.1:8080/api/local-ai/models/chat/install" `
  -H "Content-Type: application/json" `
  -d '{"confirmed":true}'

curl.exe "http://127.0.0.1:8080/api/local-ai/model-installs/<jobId>"
curl.exe -X POST "http://127.0.0.1:8080/api/local-ai/model-installs/<jobId>/cancel"
```

설치 진행률은 Ollama가 내려주는 현재 모델 파일의 바이트 기준이며, 완료 뒤 AI 상태를 다시 확인한다. 설치 작업 상태는 현재 프로세스 메모리에만 보관되므로 애플리케이션을 재시작하면 같은 모델 설치를 다시 요청해 Ollama의 이어받기 동작을 사용한다.

## GUI 개발 실행

백엔드를 실행한 상태에서 별도 PowerShell 창을 열어 다음을 실행한다.

```powershell
Set-Location frontend
npm.cmd ci
npm.cmd run dev
```

개발 화면은 `http://127.0.0.1:5173`에서 열린다. Vite 개발 서버는 `/api` 요청만 `127.0.0.1:8080`의 Spring Boot로 전달한다. 화면 자체에는 외부 글꼴, 분석 도구나 원격 리소스 요청이 없다.

현재 GUI에는 흰색·보라색 기반의 홈 화면, 네이티브 파일·폴더 수집, 의미 검색, SSE 실시간 답변, 인용 근거, 기능별 비활성화 안내와 우측 상단 백엔드·AI 개별 상태 표시가 구현돼 있다. Tauri Windows 창은 루프백 백엔드만 호출하며, 설치본에서는 전용 PostgreSQL과 Spring Boot를 자동으로 시작하고 앱 종료 시 함께 안전하게 종료한다. 업데이트 설치나 삭제 시에도 실행 중인 앱에 먼저 정상 종료를 요청하고 최대 30초 동안 기다린다. 이전 버전의 앱이 종료 요청을 지원하지 않을 때만 사용자 확인 후 앱을 종료하며, 전용 PostgreSQL이 안전하게 정지되지 않으면 런타임 파일을 덮어쓰지 않고 설치를 중단한다. 창이 백엔드보다 먼저 표시되는 정상 시작 순서를 고려해 백엔드와 AI 상태를 각각 최대 30초 동안 1초 간격으로 확인한다. 한쪽 확인이 먼저 끝나도 다른 쪽 확인은 중단하지 않는다. 최초 실행 안내는 두 상태의 `확인 대기`, `확인 중`, `준비됨` 또는 `확인 실패`와 개별 시도 횟수를 구분해 표시하며, 완료되면 위쪽으로 부드럽게 접히면서 아래 콘텐츠가 자연스럽게 자리를 채운다. 사용자가 AI 상태 팝업에서 수동으로 다시 확인할 때는 본문 준비 영역을 추가하지 않고 열린 팝업 안에서만 진행 상태를 보여준다.

개발용 Windows 실행 파일은 다음 명령으로 만든다.

```powershell
Set-Location frontend
npm.cmd run desktop:build
```

출력은 `frontend/src-tauri/target/release/PrivateKB.exe`다. `runtime/manifest.json`이 없는 개발 빌드는 기존처럼 외부 Spring Boot와 Docker PostgreSQL을 사용한다.

전용 Java·PostgreSQL·pgvector를 포함한 개발 검증용 설치 파일은 다음 명령으로 만든다.

```powershell
Set-Location frontend
npm.cmd run desktop:bundle
```

이 명령은 고정 의존성 다운로드·검증, pgvector Windows 빌드, 런타임 배치, 독립 DB/API 검증, Java·프런트엔드·Rust 테스트와 NSIS 생성을 수행한다. 출력은 `frontend/src-tauri/target/release/bundle/nsis/PrivateKB_0.1.0_x64-setup.exe`이며 체크섬과 빌드 정보 파일도 함께 생성한다. [Fork·패키징 안내](docs/development/fork-and-build.md)를 참고한다.

## 문서 업로드와 파싱

현재 지원 확장자는 `.pdf`, `.doc`, `.docx`, `.ppt`, `.pptx`, `.xls`, `.xlsx`, `.hwp`, `.md`, `.markdown`, `.txt`다. HWP는 OLE2 기반 HWP 5.x만 지원하며 HWP 3.x와 HWPX는 아직 대상이 아니다. Office와 HWP의 매크로, 삭제된 내용과 문서에 포함된 별도 파일은 실행하거나 재귀 파싱하지 않는다. 암호로 보호되어 본문을 읽을 수 없는 문서는 파싱 실패로 처리한다.

2주차에는 인증 기능이 아직 없으므로 마이그레이션에서 만든 기본 로컬 작업공간을 사용한다.

```text
00000000-0000-0000-0000-000000000001
```

애플리케이션을 실행한 뒤 직접 준비한 테스트 TXT 문서를 올리는 예시는 다음과 같다. `sample.txt`는 실제 파일 경로로 바꾼다.

```powershell
curl.exe -X POST `
  "http://127.0.0.1:8080/api/workspaces/00000000-0000-0000-0000-000000000001/documents" `
  -F "file=@sample.txt;type=text/plain"
```

응답의 `ingestionJobId`로 상태를 확인하거나 실패한 작업을 재시도한다.

```powershell
curl.exe "http://127.0.0.1:8080/api/ingestions/<ingestionJobId>"
curl.exe -X POST "http://127.0.0.1:8080/api/ingestions/<ingestionJobId>/retry"
```

새 업로드는 HTTP 202, 같은 워크스페이스의 동일 바이트 업로드는 기존 문서 버전과 작업을 HTTP 200으로 반환한다. 상태는 `RECEIVED`, `STORED`, `PARSING`, `PARSED`, `FAILED` 순으로 추적한다.

기본 제한은 업로드 25MiB, 추출문 500만 자, 파싱 30초, 최대 3회 시도다. 원문과 추출문은 `data/privatekb` 아래 서버 생성 경로에 저장되며 Git에서 제외된다. 6주차 암호화가 구현되기 전에는 실제 민감 문서를 사용하지 않는다.

## 문서 색인과 의미 검색

파싱이 끝나면 문서를 기본 1,200자, 180자 겹침 단위로 나누고 `qwen3-embedding:0.6b`로 1024차원 벡터를 생성한다. 색인 작업은 `PENDING`, `MODEL_WAITING`, `REINDEX_REQUIRED`, `INDEXING`, `INDEXED`, `FAILED` 상태로 별도 추적한다. Ollama나 임베딩 모델이 없어도 업로드와 파싱은 완료되며 색인만 대기한다.

AI 상태를 수동으로 다시 확인해 임베딩 모델이 준비되면 대기 작업을 자동 재개한다. 모델 다이제스트가 달라진 기존 색인은 검색 후보에서 제외하고 `REINDEX_REQUIRED`로 전환한 뒤 다시 색인한다.

화면의 지식 검색 입력창을 사용하거나 다음 API로 벡터 유사도 85%, 본문 키워드·파일명 신호 15%의 혼합 검색을 실행할 수 있다. Qwen3 Embedding 권장 형식에 맞춰 검색 질의에만 검색 작업 지시문을 붙이고 문서 임베딩은 그대로 유지한다. HNSW 벡터 후보, GIN 본문 키워드 후보와 `pg_trgm` 파일명 후보를 제한된 개수만 조회한 뒤 혼합 점수를 계산한다. 일반 검색은 점수 `0.40` 미만을 제외하고, 문서 버전별 최고 점수 청크 하나만 반환하므로 낮은 관련도의 문서나 같은 파일이 반복되지 않는다. 이 점수는 일치 확률이 아니며 실제 평가 자료로 계속 보정한다.

```powershell
curl.exe -X POST `
  "http://127.0.0.1:8080/api/workspaces/00000000-0000-0000-0000-000000000001/search" `
  -H "Content-Type: application/json" `
  -d '{"query":"최근 장애 대응 결정 사항","limit":5}'
```

현재 인증·사용자별 ACL은 아직 구현 전이다. 검색 SQL은 기본 작업공간을 먼저 제한하며 실제 사용자·폴더·문서 ACL 조건은 권한 개발 단계에서 추가한다. 그 전까지는 합성 자료만 사용한다.

## 근거 기반 답변

임베딩과 설정에서 선택한 대화 모델이 모두 준비되면 화면의 질문 영역이나 다음 SSE API를 사용할 수 있다. 서버는 먼저 선택한 로컬 대화 모델로 사용자 요청을 분석해 검색 핵심어, 파일명 단서와 확장자를 구조화한다. `어제` 같은 상대 날짜는 한국 시간 기준 절대 날짜로 바꾸고 `찾아서 요약해줘` 같은 작업 지시는 검색 대상과 분리한다. 이 검색 계획으로 찾은 점수 `0.50` 이상 문서 구간만 최종 답변 문맥에 넣고 관련 파일을 인용으로 함께 반환한다. 기준을 충족하는 근거가 없으면 최종 답변 생성을 실행하지 않고 근거 부족으로 거절한다. 이 값은 현재 자료의 오탐과 누락을 함께 줄이기 위한 임시 기준이며 평가 자료가 늘어나면 정밀도와 재현율을 측정해 조정한다.

```powershell
curl.exe --no-buffer -X POST `
  "http://127.0.0.1:8080/api/workspaces/00000000-0000-0000-0000-000000000001/answers/stream" `
  -H "Accept: text/event-stream" `
  -H "Content-Type: application/json" `
  -d '{"question":"최근 장애 대응의 후속 조치를 알려줘"}'
```

응답은 `citations`, `token`, `refusal` 또는 `error`, `complete` 이벤트 순서로 전달된다. 답변과 인용 원문은 브라우저 캐시에 남기지 않도록 `Cache-Control: no-store`를 사용한다. 동시 생성은 로컬 GPU 메모리를 보호하기 위해 한 건으로 제한하고 나머지는 제한된 대기열에서 처리한다.

## 문서 수집 모듈의 코드 구조

`src/main/java/io/privatekb/ingestion`은 기능 모듈 경계를 유지하면서 내부를 역할별로 나눈다.

```text
ingestion/
├── package-info.java          Spring Modulith 모듈 경계
└── internal/
    ├── web/                   HTTP 요청·응답 변환, Controller, 예외 응답
    ├── application/           문서 등록·조회·재시도·수집 작업 조율
    │   ├── command/           사용 사례의 입력
    │   ├── view/              조회 결과와 사용 사례의 반환값
    │   └── port/              저장·조회·파싱·비동기 실행 인터페이스
    ├── domain/                문서 상태, 청크, 업로드 검증 정책과 오류
    ├── parsing/               Tika 파싱, Tesseract OCR, 임시 파일 정리
    ├── indexing/              임베딩 실행, 체크포인트와 시작 시 복구
    ├── persistence/           JDBC 구현과 SQL
    └── config/                설정 바인딩과 실행기 Bean 구성
```

현재 다른 기능 모듈이 직접 사용하는 Java 계약은 없으므로 루트에 공개 타입을 두지 않는다.
`internal`의 `public` 타입은 형제 패키지에서 참조하기 위한 것이며 다른 모듈에 공개하는 API가 아니다.
Controller는 사용 사례를 호출하고, 사용 사례는 `port` 인터페이스를 통해 구현체를 사용한다.
`domain`의 기존 Spring 컴포넌트·설정 의존성은 유지하며, 프레임워크와 완전히 분리된 도메인 모델을 의미하지는 않는다.

테스트도 대상 클래스의 패키지에 맞춘다. `IngestionArchitectureTest`는 내부 역할 간 의존 방향을,
`ModularArchitectureTest`는 다른 기능 모듈에서 내부 구현을 참조하지 않는지 검증한다.
HTTP API, DB 스키마, 설정 키와 문서 처리 규칙은 패키지 재구성으로 변경하지 않는다.

## 공개 저장소 구성

- `src/`: Java 소스, DB 마이그레이션, 자동화 테스트
- `frontend/`: React·Tauri 소스, 앱 아이콘, 자동화 테스트, 의존성 잠금 파일
- `gradle/`, Gradle 설정과 Wrapper: 백엔드 빌드 구성
- `scripts/`: Windows 의존성 준비·검증·패키징 스크립트와 고정 버전 정보
- `.github/workflows/`: Windows 설치 파일 빌드 워크플로
- [Fork·빌드 안내](docs/development/fork-and-build.md)

기획서, 개발 보고서, UI 시안, 수동 테스트 문서와 데이터 생성 도구는 공개 대상에서 제외한다.
자동화 테스트 코드는 포함하며 PDF 통합 테스트 자료는 실행 중 생성한다.
설치 파일은 소스 커밋에 넣지 않고 GitHub Releases의 다운로드 파일로 제공한다.

## 설치본 실행 로그 관리

문서 목록과 DB의 작업 상태·상태 변경 이력은 그대로 유지한다. 아래 정책은
`%LOCALAPPDATA%\io.privatekb.desktop\logs`의 실행 로그에만 적용한다.

- `runtime.log`: 앱 서비스 시작·종료와 오류. UTC 시각과 INFO/WARN/ERROR를 기록한다.
- `backend.log`: Java 표준 출력·오류를 8KB 버퍼로 수집한다. 10,000,000바이트 또는 UTC 날짜 변경 시 보관 파일로 전환한다.
- `runtime.log`도 같은 크기·날짜 회전 정책을 사용한다.
- `postgresql.log`: PostgreSQL 로그 수집기가 시작되기 전의 시작 진단 로그. 서버 시작 전에 기존 파일을 보관 파일로 전환한다.
- `postgresql-YYYYMMDD_HHMMSS.log`: PostgreSQL 자체 로그 수집기의 실제 서버 로그. 약 10MB(9765KiB) 또는 UTC 날짜 변경 시 회전한다.
- 보관 로그는 마지막 기록 후 7일이 지나면 삭제한다. 관리 대상 로그 합계가 100MB를 넘으면 오래된 보관 로그부터 정리한다.
- 앱 시작 시와 실행 중 1분 간격으로 정리한다. 앱·Java 로그 회전 시에도 정리하며, 현재 기록 중인 파일은 삭제하지 않는다.

PostgreSQL은 `current_logfiles`로 활성 파일을 확인한다. 활성 파일을 확인할 수 없으면
안전하게 PostgreSQL 보관 로그 삭제를 보류한다. 문서·DB 폴더, 알 수 없는 파일명,
하위 폴더와 심볼릭 링크는 정리 대상이 아니다.

이 크기는 회전·정리 기준이지 디스크 할당량의 절대 상한은 아니다. PostgreSQL은 로그 레코드
단위로 회전하고 정리는 주기적으로 수행하므로 순간적으로 초과할 수 있다. 기존 대형 로그는
업데이트 후 보관 파일로 전환되고 보관 기간·전체 용량 정책에 따라 정리된다.
삭제 권한이나 파일 잠금으로 정리에 실패하면 다음 주기에 재시도한다.
Java 로그 저장 실패 시에도 출력 파이프를 계속 읽어 서비스가 로그 때문에 멈추지 않도록 한다.

개발 모드의 터미널 출력과 `build/` 아래의 테스트·패키징 진단 로그는 이 설치본 정책의 대상이 아니다.
이미 실행 중인 이전 버전 서비스에는 즉시 적용되지 않으므로 업데이트 후 앱을 완전히 종료하고 다시 실행한다.
PostgreSQL 회전 설정의 의미는 [공식 로그 설정 문서](https://www.postgresql.org/docs/18/runtime-config-logging.html)를 참고한다.

## 개인정보 보호 원칙

- `.env`, 키, 사용자 원문, 추출문, 청크, 임베딩, 캐시, 프롬프트와 답변을 저장소에 올리지 않는다.
- 테스트 자료와 시연에는 실제 개인정보를 사용하지 않는다.
- 문서 본문, 검색 청크, 프롬프트와 생성 답변을 일반 로그에 기록하지 않는다.
- 데이터베이스 검색 쿼리 자체에 권한 조건을 포함하며, 검색 후 ACL 필터링을 금지한다.
- 임베딩 모델이 바뀌면 전체 문서를 다시 색인한다.

위 항목은 제품 전체의 제약 조건이다. 1주차 프로젝트 골격이 이후 주차의 모든 보안 통제를 이미 구현했다는 뜻은 아니다.
