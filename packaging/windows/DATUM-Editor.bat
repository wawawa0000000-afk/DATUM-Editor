@echo off
rem DATUM-Editor launcher (uses the Java runtime bundled in the "runtime" folder).
rem Files given as arguments (or dropped on this file) are opened; paths stay relative to where it was started.
if not exist "%~dp0runtime\bin\javaw.exe" (
  echo The "runtime" folder was not found next to this file. Extract the whole ZIP first.
  pause
  exit /b 1
)
start "" "%~dp0runtime\bin\javaw.exe" -jar "%~dp0minieditor.jar" %*
