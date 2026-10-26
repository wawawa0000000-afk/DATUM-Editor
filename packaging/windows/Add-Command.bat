@echo off
rem Registers this folder in your PATH so that typing "datum" starts DATUM-Editor.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0command-path.ps1" -Dir "%~dp0."
pause
