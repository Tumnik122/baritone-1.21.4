@echo off
setlocal
cd /d "%~dp0"
echo ==========================================================
echo   URUCHAMIANIE 2 BOTOW Z AUTOMATYCZNA FARMA (#farm)
echo ==========================================================
call "%~dp0start-bots.bat" 2 -Task farm %*
endlocal
