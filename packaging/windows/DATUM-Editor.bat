@echo off
rem DATUM-Editor launcher (uses the Java runtime bundled in the "runtime" folder).
rem Files given as arguments (or dropped on this file) are opened; paths stay relative to where it was started.
if not exist "%~dp0runtime\bin\javaw.exe" (
  echo The "runtime" folder was not found next to this file. Extract the whole ZIP first.
  pause
  exit /b 1
)
rem Startup speed: the class-data archive minieditor.jsa is made by Java on the first run (and again after an update). If it is missing or unusable, Java starts normally without a message.
start "" "%~dp0runtime\bin\javaw.exe" -Xlog:disable -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile="%~dp0minieditor.jsa" -jar "%~dp0minieditor.jar" %*
