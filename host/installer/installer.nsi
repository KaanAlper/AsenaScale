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

; Stop a running copy so its files can be replaced / removed.
!macro StopApp
  nsExec::Exec 'taskkill /F /IM asenascale.exe'
  Pop $0
  Sleep 400
!macroend

Section "Install"
  !insertmacro StopApp
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
  !insertmacro StopApp
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
