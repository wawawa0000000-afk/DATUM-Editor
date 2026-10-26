@echo off
rem Removes this folder from your PATH (undoes Add-Command.bat).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0command-path.ps1" -Dir "%~dp0." -Remove
pause
