; PC Remote Host installer hooks (Tauri NSIS). The installer runs elevated (perMachine), or as
; SYSTEM with /S when the service installs an update.
; The bundled ViGEmBus installer comes from host/scripts/fetch-deps.ps1.

!define PCR_RUN_KEY "Software\Microsoft\Windows\CurrentVersion\Run"
!define PCR_FW_RULE "PC Remote Host"
!define PCR_SVC "PC Remote"
; Resolved here: inside a macro, __FILEDIR__ would be the generated script's folder.
!define PCR_DEPS "${__FILEDIR__}\deps"

; Stop the service (it ends its server) and the tray before the files are replaced. Runs before
; Tauri's own "app is running" check, so that check finds nothing to kill.
!macro NSIS_HOOK_PREINSTALL
  nsExec::Exec 'sc.exe stop "${PCR_SVC}"'
  ; up to ~10 s for the service to reach STOPPED (skipped when it isn't installed yet)
  StrCpy $1 0
  pcr_wait_stop:
    nsExec::Exec 'cmd.exe /c sc.exe query "${PCR_SVC}" | findstr /c:"RUNNING" /c:"PENDING"'
    Pop $0
    ${If} $0 == 0
    ${AndIf} $1 < 20
      IntOp $1 $1 + 1
      Sleep 500
      Goto pcr_wait_stop
    ${EndIf}
  pcr_stopped:
  nsExec::Exec 'taskkill.exe /F /IM pc-remote-host.exe'
  ; 2.x: the elevated start task and the per-user autostart are replaced by the service
  nsExec::Exec 'schtasks.exe /Delete /TN "PC Remote" /F'
  DeleteRegValue HKCU "${PCR_RUN_KEY}" "PC Remote"
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ; ViGEmBus gamepad driver, only when absent.
  nsExec::Exec 'sc.exe query ViGEmBus'
  Pop $0
  ${If} $0 != 0
    DetailPrint "Installing the gamepad driver…"
    File "/oname=$PLUGINSDIR\ViGEmBus.exe" "${PCR_DEPS}\ViGEmBus_Setup.exe"
    ExecWait '"$PLUGINSDIR\ViGEmBus.exe" /exenoui /qn /norestart' $0
    DetailPrint "ViGEmBus setup exit code: $0"
  ${EndIf}

  ; Phones reach the agent on TCP 47999 (TLS, token-authenticated) and stream over UDP (47998 when free).
  nsExec::Exec 'netsh advfirewall firewall delete rule name="${PCR_FW_RULE}"'
  nsExec::Exec 'netsh advfirewall firewall add rule name="${PCR_FW_RULE}" dir=in action=allow protocol=TCP localport=47999 program="$INSTDIR\pc-remote-host.exe" profile=any'
  nsExec::Exec 'netsh advfirewall firewall add rule name="${PCR_FW_RULE}" dir=in action=allow protocol=UDP program="$INSTDIR\pc-remote-host.exe" profile=any'

  ; The service: starts at boot (before sign-in), restarts after a crash.
  DetailPrint "Registering the PC Remote service…"
  nsExec::Exec 'sc.exe query "${PCR_SVC}"'
  Pop $0
  ${If} $0 == 0
    nsExec::Exec 'sc.exe config "${PCR_SVC}" binPath= "\"$INSTDIR\pc-remote-host.exe\" --service" start= auto obj= LocalSystem'
  ${Else}
    nsExec::Exec 'sc.exe create "${PCR_SVC}" binPath= "\"$INSTDIR\pc-remote-host.exe\" --service" start= auto obj= LocalSystem DisplayName= "PC Remote"'
  ${EndIf}
  nsExec::Exec 'sc.exe description "${PCR_SVC}" "Lets your paired phone see and control this PC, at the sign-in screen too."'
  nsExec::Exec 'sc.exe failure "${PCR_SVC}" reset= 86400 actions= restart/5000/restart/5000/restart/30000'

  ; Wake-on-LAN from shutdown: Fast Startup (hybrid hibernate) ignores magic packets on many PCs,
  ; and adapters need "wake on magic packet" on.
  WriteRegDWORD HKLM "SYSTEM\CurrentControlSet\Control\Session Manager\Power" "HiberbootEnabled" 0
  nsExec::Exec `powershell.exe -NoProfile -Command "Get-NetAdapter -Physical | ForEach-Object { Set-NetAdapterPowerManagement -Name $$_.Name -WakeOnMagicPacket Enabled -ErrorAction SilentlyContinue; powercfg /deviceenablewake $$_.InterfaceDescription }"`

  nsExec::Exec 'sc.exe start "${PCR_SVC}"'

  ; Upgrading from v1: PC Remote no longer uses Sunshine. Offer to remove it (never forced;
  ; silent installs keep it).
  nsExec::Exec 'sc.exe query SunshineService'
  Pop $0
  ${If} $0 == 0
    MessageBox MB_YESNO|MB_ICONQUESTION "PC Remote no longer needs Sunshine (it now streams with its own engine).$\r$\n$\r$\nRemove Sunshine?$\r$\nChoose No if other apps use it." /SD IDNO IDNO pcr_keep_sunshine
      DetailPrint "Removing Sunshine…"
      nsExec::Exec `powershell.exe -NoProfile -Command "Get-ItemProperty HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\* | Where-Object { $$_.DisplayName -like 'Sunshine*' -and $$_.PSChildName -like '{*' } | ForEach-Object { Start-Process msiexec.exe -Wait -ArgumentList '/x', $$_.PSChildName, '/qn', '/norestart' }"`
    pcr_keep_sunshine:
  ${EndIf}
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  nsExec::Exec 'sc.exe stop "${PCR_SVC}"'
  Sleep 2000
  nsExec::Exec 'sc.exe delete "${PCR_SVC}"'
  nsExec::Exec 'taskkill.exe /F /IM pc-remote-host.exe'
  nsExec::Exec 'netsh advfirewall firewall delete rule name="${PCR_FW_RULE}"'
  nsExec::Exec 'schtasks.exe /Delete /TN "PC Remote" /F'
  DeleteRegValue HKCU "${PCR_RUN_KEY}" "PC Remote"
  DeleteRegKey HKCU "Software\Classes\*\shell\PCRemote.Send"
!macroend
