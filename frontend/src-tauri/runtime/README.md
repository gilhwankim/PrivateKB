# Windows 전용 런타임 배치 영역

이 디렉터리는 `scripts/stage-windows-runtime.ps1`가 생성한 배포 리소스를 받는다.
Java 런타임, Spring Boot 실행 JAR, PostgreSQL·pgvector와 Tesseract OCR 바이너리는
저장소에 커밋하지 않는다.

저장소 루트의 `scripts/build-windows.ps1`가 고정된 공식 배포물을
`.build-deps`에 준비하고 이 폴더를 구성한다. 개발 PC에 설치된 PostgreSQL·OCR를
기본 소스로 사용하지 않는다. 다운로드 고정 정보도 설치 리소스에 함께 보관한다.

배포용 OCR 런타임에는 `tesseract.exe`, 실행에 필요한 DLL, `kor`·`eng`의
`tessdata_fast` 언어 모델과 각 라이선스만 포함한다. Ollama와 대화·임베딩 모델은
기존 정책대로 포함하지 않는다.

개발 모드에서는 이 디렉터리에 `manifest.json`이 없으므로 Tauri가 외부에서 실행 중인
Spring Boot와 Docker PostgreSQL을 그대로 사용한다. 설치 파일을 만들 때는 스크립트의
사전 검증을 모두 통과한 완전한 런타임만 이 위치에 배치한다. 설치본에서 백엔드는
이 디렉터리의 OCR 실행 파일과 언어 모델을 절대 경로로 지정하므로 사용자가
Tesseract를 별도로 설치하거나 `PATH`를 설정할 필요가 없다.

Java 런타임에는 구형 Word·Office 문서의 EUC-KR, MS949, Big5 등 인코딩을
지원하는 `jdk.charsets`를 반드시 포함한다. 배치 스크립트는 새로 생성한 Java로
`packagedRuntimeTest`를 통과한 뒤에만 기존 배포 리소스를 교체한다.
