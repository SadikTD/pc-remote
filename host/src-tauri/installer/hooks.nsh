; PC Remote Host installer hooks (Tauri NSIS). The installer runs elevated (perMachine).
; The bundled ViGEmBus installer comes from host/scripts/fetch-deps.ps1.

!define PCR_RUN_KEY "Software\Microsoft\Windows\CurrentVersion\Run"
!define PCR_FW_RULE "PC Remote Host"
!define PCR_TASK "PC Remote"
; Resolved here: inside a macro, __FILEDIR__ would be the generated script's folder.
!define PCR_DEPS "${__FILEDIR__}\deps"

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

  WriteRegStr HKCU "${PCR_RUN_KEY}" "PC Remote" '"$INSTDIR\pc-remote-host.exe" --hidden'

  ; On-demand task that starts the host elevated without a UAC prompt, so its input reaches admin
  ; windows (Task Manager…). Normal launches hand over to it (src/win/elevate.rs). No time limit,
  ; normal priority (tasks default to below normal), runs on battery.
  DetailPrint "Registering the elevated start task…"
  nsExec::Exec `powershell.exe -NoProfile -Command "$$a = New-ScheduledTaskAction -Execute '$INSTDIR\pc-remote-host.exe' -Argument '--hidden'; $$p = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Highest; $$s = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -Priority 4 -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew; Register-ScheduledTask -TaskName '${PCR_TASK}' -Action $$a -Principal $$p -Settings $$s -Force"`

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
  nsExec::Exec 'netsh advfirewall firewall delete rule name="${PCR_FW_RULE}"'
  DeleteRegValue HKCU "${PCR_RUN_KEY}" "PC Remote"
  nsExec::Exec 'schtasks.exe /Delete /TN "${PCR_TASK}" /F'
  DeleteRegKey HKCU "Software\Classes\*\shell\PCRemote.Send"
!macroend
