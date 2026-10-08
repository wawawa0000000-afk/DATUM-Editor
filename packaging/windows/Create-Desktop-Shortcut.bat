@echo off
rem Creates a "DATUM-Editor" shortcut (with icon) on the desktop.
rem Run this again if you move this folder to another place.
set "APPDIR=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$d = $env:APPDIR.TrimEnd('\'); $lnk = Join-Path ([Environment]::GetFolderPath('Desktop')) 'DATUM-Editor.lnk'; $s = (New-Object -ComObject WScript.Shell).CreateShortcut($lnk); $s.TargetPath = Join-Path $d 'runtime\bin\javaw.exe'; $s.Arguments = '-jar \"' + (Join-Path $d 'minieditor.jar') + '\"'; $s.WorkingDirectory = $d; $s.IconLocation = Join-Path $d 'icon.ico'; $s.Description = 'DATUM-Editor'; $s.Save(); Write-Host ('Created: ' + $lnk)"
if errorlevel 1 (
  echo Could not create the shortcut.
)
pause
