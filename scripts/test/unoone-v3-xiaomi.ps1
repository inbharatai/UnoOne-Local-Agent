# Collection only. No task execution, install, or automatic permission grants.
param([Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)][string]$Apk,[Parameter(Mandatory=$true)][string]$Out,[ValidateRange(0,86400)][int]$Seconds=600)
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Out=(Resolve-Path $Out).Path
Get-Command adb | Out-Null
& adb -s $Serial get-state | Out-File "$Out/adb-state.txt"
if ($LASTEXITCODE -ne 0) { throw 'Device unavailable: qualification pending' }
Get-FileHash $Apk -Algorithm SHA256 | Format-List | Out-File "$Out/apk-sha256.txt"
"Operator-consented install: adb -s $Serial install -r `"$Apk`". Enable Accessibility manually in Settings; never auto-granted." | Out-File "$Out/install-instructions.txt"
& adb -s $Serial shell getprop | Out-File "$Out/getprop.txt"
& adb -s $Serial shell dumpsys package com.unoone.agent | Out-File "$Out/package.txt"
& adb -s $Serial shell pm path com.unoone.agent | Out-File "$Out/installed-apk-path.txt"
& adb -s $Serial shell settings get secure enabled_accessibility_services | Out-File "$Out/accessibility-before.txt"
& adb -s $Serial shell settings get global airplane_mode_on | Out-File "$Out/airplane-observation.txt"
& adb -s $Serial shell dumpsys connectivity | Out-File "$Out/connectivity-before.txt"
& adb -s $Serial shell dumpsys netstats | Out-File "$Out/netstats-before.txt"
$log=Start-Process adb -ArgumentList @('-s',$Serial,'logcat','-v','threadtime') -RedirectStandardOutput "$Out/logcat.txt" -RedirectStandardError "$Out/logcat-errors.txt" -PassThru -NoNewWindow
try {
 Write-Host 'Manually run sustained-50 tasks. This collector does not execute or score tasks.'
 for ($t=0;$t -le $Seconds;$t+=10) {
  (Get-Date).ToUniversalTime().ToString('o') | Add-Content "$Out/samples.txt"
  & adb -s $Serial shell dumpsys meminfo com.unoone.agent | Add-Content "$Out/samples.txt"
  & adb -s $Serial shell dumpsys thermalservice | Add-Content "$Out/samples.txt"
  & adb -s $Serial shell dumpsys battery | Add-Content "$Out/samples.txt"
  if ($t -lt $Seconds) { Start-Sleep -Seconds 10 }
 }
 & adb -s $Serial logcat -b crash -d -v threadtime | Out-File "$Out/crash.txt"
 & adb -s $Serial shell dumpsys activity exit-info com.unoone.agent | Out-File "$Out/exit-info.txt"
 & adb -s $Serial shell dumpsys dropbox --print data_app_anr | Out-File "$Out/anr.txt"
 & adb -s $Serial shell dumpsys connectivity | Out-File "$Out/connectivity-after.txt"
 & adb -s $Serial shell dumpsys netstats | Out-File "$Out/netstats-after.txt"
} finally { if (!$log.HasExited) { Stop-Process -Id $log.Id } }
'PENDING: manual task evidence, exact model/runtime identity, installed APK comparison and connection-capture review required.' | Out-File "$Out/qualification.txt"
