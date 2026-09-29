@echo off
rem ChibiStudio relocator (Windows): run it once after unpacking ChibiStudio in
rem a directory other than C:\ChibiStudio, or after moving it. Close ChibiStudio
rem first. It updates the Eclipse workspaces and project locations to the
rem current directory; see relocator.jar for the details.
rem No parenthesized blocks below: the install path may contain ( and ).
setlocal
set "RELOCATOR_DIR=%~dp0"
for %%i in ("%RELOCATOR_DIR%..\..\..") do set "CHIBISTUDIO=%%~fi"

rem The Java runtime shipped with Eclipse (JustJ).
set "JAVA="
for /d %%d in ("%CHIBISTUDIO%\eclipse\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_*") do if exist "%%d\jre\bin\java.exe" set "JAVA=%%d\jre\bin\java.exe"
if defined JAVA goto run
echo ChibiStudio relocation failed: Java runtime not found in eclipse\plugins.
set "RC=1"
goto end

:run
"%JAVA%" -jar "%RELOCATOR_DIR%relocator.jar" "%CHIBISTUDIO%"
set "RC=%ERRORLEVEL%"

:end
rem Keep the window open when started by double-click.
echo "%CMDCMDLINE%" | find /i "%~nx0" >nul && pause
exit /b %RC%
