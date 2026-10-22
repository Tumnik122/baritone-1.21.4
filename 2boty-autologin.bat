@echo off
setlocal
cd /d "%~dp0"
echo ==========================================================
echo   URUCHAMIANIE 2 BOTOW Z AUTO-LOGINEM (Anarchia.gg)
echo   Bot #1 + Bot #2 z procedura logowania i wyboru trybu
echo ==========================================================
call "%~dp0start-bots.bat" 2 -Task autologin %*
endlocal
