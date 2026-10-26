@echo off
rem DATUM-Editor launcher for Windows. Double-click this file to start the editor.
rem Files given as arguments (or dropped on this file) are opened.
if exist "%~dp0target\minieditor.jar" (set "JAR=%~dp0target\minieditor.jar") else (set "JAR=%~dp0minieditor.jar")
if not exist "%JAR%" (
  echo minieditor.jar was not found. Run "mvn package" first, or put minieditor.jar next to this file.
  pause
  exit /b 1
)
where javaw >nul 2>nul
if errorlevel 1 (
  echo Java was not found. Install JDK 17 or later, for example from https://adoptium.net/
  pause
  exit /b 1
)
start "" javaw -jar "%JAR%" %*
