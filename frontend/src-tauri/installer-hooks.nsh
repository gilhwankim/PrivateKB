!macro PrivateKBStopForMaintenance
  !define PrivateKBHookId ${__LINE__}

  ${If} ${FileExists} "$INSTDIR\${MAINBINARYNAME}.exe"
    DetailPrint "PrivateKB를 안전하게 종료하는 중입니다."
    nsExec::ExecToStack '"$INSTDIR\${MAINBINARYNAME}.exe" --privatekb-maintenance-shutdown'
    Pop $R0
    Pop $R1

    ; 새 버전은 실행 중인 인스턴스에 종료 요청을 전달한다. PostgreSQL까지 정상 종료할
    ; 시간을 주고, 종료되지 않은 이전 버전에 대해서만 기존 강제 종료 안내를 표시한다.
    StrCpy $R2 0
    privatekb_wait_${PrivateKBHookId}:
      !if "${INSTALLMODE}" == "currentUser"
        nsis_tauri_utils::FindProcessCurrentUser "${MAINBINARYNAME}.exe"
      !else
        nsis_tauri_utils::FindProcess "${MAINBINARYNAME}.exe"
      !endif
      Pop $R0
      ${If} $R0 != 0
        Goto privatekb_wait_done_${PrivateKBHookId}
      ${EndIf}
      IntOp $R2 $R2 + 1
      ${If} $R2 >= 60
        Goto privatekb_wait_timeout_${PrivateKBHookId}
      ${EndIf}
      Sleep 500
      Goto privatekb_wait_${PrivateKBHookId}

    privatekb_wait_timeout_${PrivateKBHookId}:

    ; 이전 버전은 유지보수 종료 인자를 알지 못하므로 한 번만 기존 안내를 사용한다.
    ; 프로세스를 내린 뒤 아래에서 전용 PostgreSQL을 반드시 안전 종료한다.
    !insertmacro CheckIfAppIsRunning "${MAINBINARYNAME}.exe" "${PRODUCTNAME}"
    Sleep 500

    privatekb_wait_done_${PrivateKBHookId}:
  ${EndIf}

  ${If} ${FileExists} "$INSTDIR\runtime\postgresql\bin\pg_ctl.exe"
  ${AndIf} ${FileExists} "$LOCALAPPDATA\io.privatekb.desktop\database\postmaster.pid"
    DetailPrint "PrivateKB 데이터 저장소를 안전하게 종료하는 중입니다."
    nsExec::ExecToStack '"$INSTDIR\runtime\postgresql\bin\pg_ctl.exe" status -D "$LOCALAPPDATA\io.privatekb.desktop\database"'
    Pop $R0
    Pop $R1
    ${If} $R0 = 0
      nsExec::ExecToStack '"$INSTDIR\runtime\postgresql\bin\pg_ctl.exe" stop -D "$LOCALAPPDATA\io.privatekb.desktop\database" -m fast -w -t 30'
      Pop $R0
      Pop $R1
      ${If} $R0 != 0
        MessageBox MB_ICONSTOP|MB_OK "PrivateKB 데이터 저장소를 안전하게 종료하지 못했습니다.$\r$\nPrivateKB를 종료한 뒤 다시 시도해 주세요."
        Abort
      ${EndIf}
    ${ElseIf} $R0 != 3
      MessageBox MB_ICONSTOP|MB_OK "PrivateKB 데이터 저장소 상태를 확인하지 못했습니다.$\r$\nPrivateKB를 종료한 뒤 다시 시도해 주세요."
      Abort
    ${EndIf}
  ${EndIf}

  !undef PrivateKBHookId
!macroend

!macro NSIS_HOOK_PREINSTALL
  !insertmacro PrivateKBStopForMaintenance
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  !insertmacro PrivateKBStopForMaintenance
!macroend
