# Downloads the installers bundled into the PC Remote Host setup:
#   Sunshine (screen sharing) from SadikTD/Sunshine releases, and the ViGEmBus gamepad driver.
# Output: host/src-tauri/installer/deps/ (git-ignored). Run before `npm run tauri build`.
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$deps = Join-Path $PSScriptRoot "..\src-tauri\installer\deps"
New-Item -ItemType Directory -Force $deps | Out-Null

$files = @{
  "Sunshine-Windows-AMD64-installer.msi" = "https://github.com/SadikTD/Sunshine/releases/latest/download/Sunshine-Windows-AMD64-installer.msi"
  "ViGEmBus_Setup.exe" = "https://github.com/nefarius/ViGEmBus/releases/download/v1.22.0/ViGEmBus_1.22.0_x64_x86_arm64.exe"
}
foreach ($name in $files.Keys) {
  $out = Join-Path $deps $name
  if (Test-Path $out) { Write-Host "have $name"; continue }
  Write-Host "downloading $name"
  Invoke-WebRequest -Uri $files[$name] -OutFile $out
}
Get-ChildItem $deps | ForEach-Object {
  "{0}  {1,10:N0}  {2}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $_.Length, $_.Name
}
