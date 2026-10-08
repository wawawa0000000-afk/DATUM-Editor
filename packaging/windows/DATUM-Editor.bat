@echo off
rem DATUM-Editor launcher (uses the Java runtime bundled in the "runtime" folder).
cd /d "%~dp0"
if not exist "runtime\bin\javaw.exe" (
  echo The "runtime" folder was not found next to this file. Extract the whole ZIP first.
  pause
  exit /b 1
)
start "" "runtime\bin\javaw.exe" -jar "minieditor.jar"
