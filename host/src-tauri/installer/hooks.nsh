; PC Remote Host installer hooks (Tauri NSIS). The installer runs elevated (perMachine).
; Bundled installers come from host/scripts/fetch-deps.ps1.

!define PCR_RUN_KEY "Software\Microsoft\Windows\CurrentVersion\Run"
!define PCR_FW_RULE "PC Remote Host"
; Resolved here: inside a macro, __FILEDIR__ would be the generated script's folder.
!define PCR_DEPS "${__FILEDIR__}\deps"

!macro NSIS_HOOK_POSTINSTALL
  ; Sunshine: install only when absent, so an existing setup is never downgraded or reset.
  nsExec::Exec 'sc.exe query SunshineService'
  Pop $0
  ${If} $0 != 0
    DetailPrint "Installing Sunshine (screen sharing)…"
    File "/oname=$PLUGINSDIR\Sunshine.msi" "${PCR_DEPS}\Sunshine-Windows-AMD64-installer.msi"
    ExecWait 'msiexec.exe /i "$PLUGINSDIR\Sunshine.msi" /qn /norestart' $0
    DetailPrint "Sunshine setup exit code: $0"
  ${EndIf}

  ; ViGEmBus gamepad driver, likewise only when absent.
  nsExec::Exec 'sc.exe query ViGEmBus'
  Pop $0
  ${If} $0 != 0
    DetailPrint "Installing the gamepad driver…"
    File "/oname=$PLUGINSDIR\ViGEmBus.exe" "${PCR_DEPS}\ViGEmBus_Setup.exe"
    ExecWait '"$PLUGINSDIR\ViGEmBus.exe" /exenoui /qn /norestart' $0
    DetailPrint "ViGEmBus setup exit code: $0"
  ${EndIf}

  ; Phones reach the agent on TCP 47999 (TLS, token-authenticated).
  nsExec::Exec 'netsh advfirewall firewall delete rule name="${PCR_FW_RULE}"'
  nsExec::Exec 'netsh advfirewall firewall add rule name="${PCR_FW_RULE}" dir=in action=allow protocol=TCP localport=47999 program="$INSTDIR\pc-remote-host.exe" profile=any'

  ; Let the (non-elevated) tray app start/stop Sunshine when it needs recovering:
  ; the default service DACL plus start/stop (RP/WP) for interactive users.
  nsExec::Exec 'sc.exe sdset SunshineService D:(A;;CCLCSWRPWPDTLOCRRC;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)(A;;CCLCSWRPWPLOCRRC;;;IU)(A;;CCLCSWLOCRRC;;;SU)'

  ; Hidden Sunshine admin credentials, DPAPI-protected for this user.
  nsExec::Exec '"$INSTDIR\pc-remote-host.exe" --init-sunshine'
  Pop $0
  DetailPrint "Sunshine credentials: $0"

  WriteRegStr HKCU "${PCR_RUN_KEY}" "PC Remote" '"$INSTDIR\pc-remote-host.exe" --hidden'
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  nsExec::Exec 'netsh advfirewall firewall delete rule name="${PCR_FW_RULE}"'
  DeleteRegValue HKCU "${PCR_RUN_KEY}" "PC Remote"
  DeleteRegKey HKCU "Software\Classes\*\shell\PCRemote.Send"

  MessageBox MB_YESNO|MB_ICONQUESTION "Also remove Sunshine (screen sharing)?$\r$\n$\r$\nChoose No if other apps use it." /SD IDNO IDNO pcr_keep_sunshine
    DetailPrint "Removing Sunshine…"
    nsExec::Exec `powershell.exe -NoProfile -Command "Get-ItemProperty HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\* | Where-Object { $$_.DisplayName -like 'Sunshine*' -and $$_.PSChildName -like '{*' } | ForEach-Object { Start-Process msiexec.exe -Wait -ArgumentList '/x', $$_.PSChildName, '/qn', '/norestart' }"`
  pcr_keep_sunshine:
!macroend
