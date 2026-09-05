package io.privatekb.ingestion.internal.domain;

public enum DesktopImportErrorCode {
    INVALID_SOURCE_LOCATION("선택한 원본 위치를 사용할 수 없습니다."),
    SOURCE_FILE_CHANGED("선택 후 원본 파일이 변경되었습니다. 다시 선택해 주세요.");

    private final String detail;

    DesktopImportErrorCode(String detail) {
        this.detail = detail;
    }

    public String detail() {
        return detail;
    }
}
