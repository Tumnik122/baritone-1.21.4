@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bot-cmd.ps1" %*
endlocal
