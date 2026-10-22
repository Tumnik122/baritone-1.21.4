@echo off
setlocal
cd /d "%~dp0"
if "%~1"=="" (
    call "%~dp0START_BOT.bat"
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-bots.ps1" %*
)
endlocal
