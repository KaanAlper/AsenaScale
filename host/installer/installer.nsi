; AsenaScale Windows installer (NSIS 3, Modern UI 2).
;
;   makensis -DVERSION=0.3.0 -DEXE=path\to\asenascale.exe -DOUT=AsenaScale-Setup.exe installer.nsi
;
; Per-user install (no admin prompt) into %LOCALAPPDATA%\Programs\AsenaScale.
; The UI language follows Windows' display language automatically (NSIS
; picks the matching one of the languages below), English otherwise.

Unicode true
SetCompressor /SOLID lzma

!ifndef VERSION
  !define VERSION "0.0.0"
!endif
!ifndef EXE
  !define EXE "..\target\x86_64-pc-windows-gnu\release\asenascale.exe"
!endif
!ifndef OUT
  !define OUT "AsenaScale-Setup.exe"
!endif

!define APP "AsenaScale"
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP}"
!define RUN_KEY "Software\Microsoft\Windows\CurrentVersion\Run"

Name "${APP}"
OutFile "${OUT}"
InstallDir "$LOCALAPPDATA\Programs\${APP}"
InstallDirRegKey HKCU "Software\${APP}" "InstallDir"
RequestExecutionLevel user
BrandingText "${APP} ${VERSION}"

VIProductVersion "${VERSION}.0"
VIAddVersionKey /LANG=1033 "ProductName" "${APP}"
VIAddVersionKey /LANG=1033 "FileDescription" "${APP} Setup"
VIAddVersionKey /LANG=1033 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=1033 "ProductVersion" "${VERSION}"
VIAddVersionKey /LANG=1033 "LegalCopyright" "AsenaScale contributors"

!include "MUI2.nsh"

!define MUI_ICON "..\asenascale.ico"
!define MUI_UNICON "..\asenascale.ico"
!define MUI_ABORTWARNING

; Finish page: run now, and "start when I sign in" (checked).
!define MUI_FINISHPAGE_RUN "$INSTDIR\asenascale.exe"
!define MUI_FINISHPAGE_SHOWREADME ""
!define MUI_FINISHPAGE_SHOWREADME_TEXT "$(AUTOSTART)"
!define MUI_FINISHPAGE_SHOWREADME_FUNCTION EnableAutostart

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

; English first: it is the fallback for any other display language.
!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "Turkish"
!insertmacro MUI_LANGUAGE "German"
!insertmacro MUI_LANGUAGE "French"
!insertmacro MUI_LANGUAGE "Spanish"
!insertmacro MUI_LANGUAGE "Italian"
!insertmacro MUI_LANGUAGE "Portuguese"
!insertmacro MUI_LANGUAGE "PortugueseBR"
!insertmacro MUI_LANGUAGE "Russian"
!insertmacro MUI_LANGUAGE "Ukrainian"
!insertmacro MUI_LANGUAGE "Polish"
!insertmacro MUI_LANGUAGE "Dutch"
!insertmacro MUI_LANGUAGE "Swedish"
!insertmacro MUI_LANGUAGE "Danish"
!insertmacro MUI_LANGUAGE "Norwegian"
!insertmacro MUI_LANGUAGE "Finnish"
!insertmacro MUI_LANGUAGE "Czech"
!insertmacro MUI_LANGUAGE "Greek"
!insertmacro MUI_LANGUAGE "Hungarian"
!insertmacro MUI_LANGUAGE "Romanian"
!insertmacro MUI_LANGUAGE "Arabic"
!insertmacro MUI_LANGUAGE "Hebrew"
!insertmacro MUI_LANGUAGE "Farsi"
!insertmacro MUI_LANGUAGE "Hindi"
!insertmacro MUI_LANGUAGE "Indonesian"
!insertmacro MUI_LANGUAGE "Vietnamese"
!insertmacro MUI_LANGUAGE "Thai"
!insertmacro MUI_LANGUAGE "Japanese"
!insertmacro MUI_LANGUAGE "Korean"
!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "TradChinese"

LangString AUTOSTART ${LANG_ENGLISH} "Start AsenaScale when I sign in"
LangString AUTOSTART ${LANG_TURKISH} "Oturum açınca AsenaScale'i başlat"
LangString AUTOSTART ${LANG_GERMAN} "AsenaScale bei der Anmeldung starten"
LangString AUTOSTART ${LANG_FRENCH} "Démarrer AsenaScale à l'ouverture de session"
LangString AUTOSTART ${LANG_SPANISH} "Iniciar AsenaScale al iniciar sesión"
LangString AUTOSTART ${LANG_ITALIAN} "Avvia AsenaScale all'accesso"
LangString AUTOSTART ${LANG_PORTUGUESE} "Iniciar o AsenaScale ao iniciar sessão"
LangString AUTOSTART ${LANG_PORTUGUESEBR} "Iniciar o AsenaScale ao entrar"
LangString AUTOSTART ${LANG_RUSSIAN} "Запускать AsenaScale при входе в систему"
LangString AUTOSTART ${LANG_UKRAINIAN} "Запускати AsenaScale під час входу"
LangString AUTOSTART ${LANG_POLISH} "Uruchamiaj AsenaScale po zalogowaniu"
LangString AUTOSTART ${LANG_DUTCH} "AsenaScale starten bij aanmelden"
LangString AUTOSTART ${LANG_SWEDISH} "Starta AsenaScale vid inloggning"
LangString AUTOSTART ${LANG_DANISH} "Start AsenaScale ved logon"
LangString AUTOSTART ${LANG_NORWEGIAN} "Start AsenaScale ved pålogging"
LangString AUTOSTART ${LANG_FINNISH} "Käynnistä AsenaScale kirjautuessa"
LangString AUTOSTART ${LANG_CZECH} "Spouštět AsenaScale po přihlášení"
LangString AUTOSTART ${LANG_GREEK} "Εκκίνηση του AsenaScale κατά τη σύνδεση"
LangString AUTOSTART ${LANG_HUNGARIAN} "Az AsenaScale indítása bejelentkezéskor"
LangString AUTOSTART ${LANG_ROMANIAN} "Pornește AsenaScale la conectare"
LangString AUTOSTART ${LANG_ARABIC} "تشغيل AsenaScale عند تسجيل الدخول"
LangString AUTOSTART ${LANG_HEBREW} "הפעל את AsenaScale בעת הכניסה"
LangString AUTOSTART ${LANG_FARSI} "اجرای AsenaScale هنگام ورود"
LangString AUTOSTART ${LANG_HINDI} "साइन इन करने पर AsenaScale शुरू करें"
LangString AUTOSTART ${LANG_INDONESIAN} "Jalankan AsenaScale saat masuk"
LangString AUTOSTART ${LANG_VIETNAMESE} "Khởi động AsenaScale khi đăng nhập"
LangString AUTOSTART ${LANG_THAI} "เริ่ม AsenaScale เมื่อลงชื่อเข้าใช้"
LangString AUTOSTART ${LANG_JAPANESE} "サインイン時に AsenaScale を起動する"
LangString AUTOSTART ${LANG_KOREAN} "로그인할 때 AsenaScale 시작"
LangString AUTOSTART ${LANG_SIMPCHINESE} "登录时启动 AsenaScale"
LangString AUTOSTART ${LANG_TRADCHINESE} "登入時啟動 AsenaScale"

LangString RUNNING ${LANG_ENGLISH} "AsenaScale is running. It will be closed, together with its terminal sessions (PowerShell, Claude and others opened for the phone). Continue?"
LangString RUNNING ${LANG_TURKISH} "AsenaScale çalışıyor. Kapatılacak; telefon için açtığı terminal oturumları (PowerShell, Claude vb.) da kapanacak. Devam edilsin mi?"
LangString RUNNING ${LANG_GERMAN} "AsenaScale läuft. Es wird geschlossen, zusammen mit seinen Terminalsitzungen (PowerShell, Claude usw.). Fortfahren?"
LangString RUNNING ${LANG_FRENCH} "AsenaScale est en cours d'exécution. Il sera fermé, ainsi que ses sessions de terminal (PowerShell, Claude, etc.). Continuer ?"
LangString RUNNING ${LANG_SPANISH} "AsenaScale está en ejecución. Se cerrará junto con sus sesiones de terminal (PowerShell, Claude, etc.). ¿Continuar?"
LangString RUNNING ${LANG_ITALIAN} "AsenaScale è in esecuzione. Verrà chiuso insieme alle sue sessioni di terminale (PowerShell, Claude, ecc.). Continuare?"
LangString RUNNING ${LANG_PORTUGUESE} "O AsenaScale está em execução. Será fechado, junto com as sessões de terminal (PowerShell, Claude etc.). Continuar?"
LangString RUNNING ${LANG_PORTUGUESEBR} "O AsenaScale está em execução. Será fechado, junto com as sessões de terminal (PowerShell, Claude etc.). Continuar?"
LangString RUNNING ${LANG_RUSSIAN} "AsenaScale запущен. Он будет закрыт вместе с его терминальными сеансами (PowerShell, Claude и др.). Продолжить?"
LangString RUNNING ${LANG_UKRAINIAN} "AsenaScale запущено. Його буде закрито разом із терміналами (PowerShell, Claude тощо). Продовжити?"
LangString RUNNING ${LANG_POLISH} "AsenaScale działa. Zostanie zamknięty razem z sesjami terminala (PowerShell, Claude itd.). Kontynuować?"
LangString RUNNING ${LANG_DUTCH} "AsenaScale draait. Het wordt gesloten, samen met de terminalsessies (PowerShell, Claude enz.). Doorgaan?"
LangString RUNNING ${LANG_SWEDISH} "AsenaScale is running. It will be closed, together with its terminal sessions (PowerShell, Claude and others opened for the phone). Continue?"
LangString RUNNING ${LANG_DANISH} "AsenaScale is running. It will be closed, together with its terminal sessions (PowerShell, Claude and others opened for the phone). Continue?"
LangString RUNNING ${LANG_NORWEGIAN} "AsenaScale is running. It will be closed, together with its terminal sessions (PowerShell, Claude and others opened for the phone). Continue?"
LangString RUNNING ${LANG_FINNISH} "AsenaScale is running. It will be closed, together with its terminal sessions (PowerShell, Claude and others opened for the phone). Continue?"
LangString RUNNING ${LANG_CZECH} "AsenaScale is running. It will be closed, together with its terminal sessions (PowerShell, Claude and others opened for the phone). Continue?"
LangString RUNNING ${LANG_GREEK} "AsenaScale is running. It will be closed, together with its terminal sessions (PowerShell, Claude and others opened for the phone). Continue?"
LangString RUNNING ${LANG_HUNGARIAN} "AsenaScale is running. It will be closed, together with its terminal sessions (PowerShell, Claude and others opened for the phone). Continue?"
LangString RUNNING ${LANG_ROMANIAN} "AsenaScale is running. It will be closed, together with its terminal sessions (PowerShell, Claude and others opened for the phone). Continue?"
LangString RUNNING ${LANG_ARABIC} "AsenaScale قيد التشغيل. سيتم إغلاقه مع جلسات الطرفية التابعة له (PowerShell وClaude وغيرها). متابعة؟"
LangString RUNNING ${LANG_HEBREW} "AsenaScale is running. It will be closed, together with its terminal sessions (PowerShell, Claude and others opened for the phone). Continue?"
LangString RUNNING ${LANG_FARSI} "AsenaScale در حال اجراست. همراه با جلسه‌های ترمینالش (PowerShell، Claude و...) بسته می‌شود. ادامه می‌دهید؟"
LangString RUNNING ${LANG_HINDI} "AsenaScale चल रहा है। इसे इसके टर्मिनल सत्रों (PowerShell, Claude आदि) के साथ बंद किया जाएगा। जारी रखें?"
LangString RUNNING ${LANG_INDONESIAN} "AsenaScale sedang berjalan. Aplikasi akan ditutup beserta sesi terminalnya (PowerShell, Claude, dll.). Lanjutkan?"
LangString RUNNING ${LANG_VIETNAMESE} "AsenaScale đang chạy. Ứng dụng sẽ đóng cùng các phiên terminal (PowerShell, Claude...). Tiếp tục?"
LangString RUNNING ${LANG_THAI} "AsenaScale กำลังทำงาน จะถูกปิดพร้อมเซสชันเทอร์มินัล (PowerShell, Claude ฯลฯ) ดำเนินการต่อหรือไม่"
LangString RUNNING ${LANG_JAPANESE} "AsenaScale が実行中です。ターミナルセッション (PowerShell、Claude など) と一緒に終了します。続行しますか？"
LangString RUNNING ${LANG_KOREAN} "AsenaScale이 실행 중입니다. 터미널 세션(PowerShell, Claude 등)과 함께 종료됩니다. 계속할까요?"
LangString RUNNING ${LANG_SIMPCHINESE} "AsenaScale 正在运行。它及其终端会话（PowerShell、Claude 等）将被关闭。是否继续？"
LangString RUNNING ${LANG_TRADCHINESE} "AsenaScale 正在執行。它及其終端機工作階段（PowerShell、Claude 等）將被關閉。是否繼續？"

;; A running AsenaScale (installed or portable) and the terminals it
;; started (PowerShell, Claude...) have to go before files are replaced or
;; removed. Interactive runs ask first; silent runs (the app's own
;; auto-update, which only happens with no terminal open) don't.
!macro RunningCheck un
Function ${un}IsRunning
  nsExec::ExecToStack 'cmd /c tasklist /NH /FI "IMAGENAME eq asenascale.exe" | find /I "asenascale"'
  Pop $0
  Pop $1
  StrCmp $0 0 found
  nsExec::ExecToStack 'cmd /c tasklist /NH /FI "IMAGENAME eq AsenaScale-windows-x64-portable.exe" | find /I "asenascale"'
  Pop $0
  Pop $1
  StrCmp $0 0 found
  StrCpy $0 0
  Return
found:
  StrCpy $0 1
FunctionEnd

Function ${un}AskToStop
  Call ${un}IsRunning
  StrCmp $0 1 0 done
  IfSilent done
  MessageBox MB_OKCANCEL|MB_ICONEXCLAMATION "$(RUNNING)" IDOK done
  Abort
done:
FunctionEnd

Function ${un}StopApp
  Call ${un}IsRunning
  StrCmp $0 1 0 done
  ; Politely first (it ends its terminal sessions itself), then for sure,
  ; with /T so child processes (shells, Claude) go too.
  IfFileExists "$INSTDIR\asenascale.exe" 0 +3
    nsExec::Exec '"$INSTDIR\asenascale.exe" quit'
    Pop $1
  Sleep 1500
  nsExec::Exec 'taskkill /F /T /IM asenascale.exe'
  Pop $1
  nsExec::Exec 'taskkill /F /T /IM AsenaScale-windows-x64-portable.exe'
  Pop $1
  Sleep 600
done:
FunctionEnd
!macroend
!insertmacro RunningCheck ""
!insertmacro RunningCheck "un."

Function .onInit
  Call AskToStop
FunctionEnd

Function un.onInit
  Call un.AskToStop
FunctionEnd

Section "Install"
  Call StopApp
  SetOutPath "$INSTDIR"
  File "/oname=asenascale.exe" "${EXE}"
  WriteUninstaller "$INSTDIR\uninstall.exe"

  CreateShortCut "$SMPROGRAMS\${APP}.lnk" "$INSTDIR\asenascale.exe"

  ; `asenascale` on the command line (cmd, PowerShell, Windows Terminal):
  ; a small shim in a folder that is on every user's PATH. Through a .cmd
  ; the terminal waits for the command and shows its output; with no
  ; arguments it just starts the tray app.
  CreateDirectory "$LOCALAPPDATA\Microsoft\WindowsApps"
  FileOpen $0 "$LOCALAPPDATA\Microsoft\WindowsApps\asenascale.cmd" w
  FileWrite $0 '@echo off$\r$\n'
  FileWrite $0 'if "%~1"=="" (start "" "$INSTDIR\asenascale.exe" & exit /b 0)$\r$\n'
  FileWrite $0 '"$INSTDIR\asenascale.exe" %*$\r$\n'
  FileWrite $0 'exit /b %errorlevel%$\r$\n'
  FileClose $0

  WriteRegStr HKCU "Software\${APP}" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "${APP}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher" "${APP}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\asenascale.exe"
  WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr HKCU "${UNINST_KEY}" "QuietUninstallString" '"$INSTDIR\uninstall.exe" /S'
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1
  ; The app manages the Run key itself too; this keeps the path current.
  ReadRegStr $0 HKCU "${RUN_KEY}" "${APP}"
  StrCmp $0 "" +2
    WriteRegStr HKCU "${RUN_KEY}" "${APP}" '"$INSTDIR\asenascale.exe"'
SectionEnd

Function EnableAutostart
  WriteRegStr HKCU "${RUN_KEY}" "${APP}" '"$INSTDIR\asenascale.exe"'
FunctionEnd

; Silent installs (/S) still get autostart and are started once.
Function .onInstSuccess
  IfSilent 0 +3
    Call EnableAutostart
    Exec '"$INSTDIR\asenascale.exe"'
FunctionEnd

Section "Uninstall"
  Call un.StopApp
  Delete "$INSTDIR\asenascale.exe"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"
  Delete "$SMPROGRAMS\${APP}.lnk"
  Delete "$LOCALAPPDATA\Microsoft\WindowsApps\asenascale.cmd"
  DeleteRegValue HKCU "${RUN_KEY}" "${APP}"
  DeleteRegKey HKCU "${UNINST_KEY}"
  DeleteRegKey HKCU "Software\${APP}"
  ; Settings and approved phones stay in %APPDATA%\AsenaScale for a reinstall.
SectionEnd
