@echo off
setlocal
cd /d "%~dp0"
echo ==========================================================
echo        URUCHAMIANIE 2 BOTOW BARITONE (Fabric 1.21.4)
echo     Bot #1 (Gamer6844)   - Okno gry 3D + Panel :21420
echo     Bot #2 (MocnyZubr80) - Okno gry 3D + Panel :21421
echo ==========================================================
call "%~dp0start-bots.bat" 2 %*
echo.
echo [OK] Boty pracuja w swoich oknach gry!
echo      Panel Glowny WWW: http://localhost:21420
echo      Panel Bota #2:    http://localhost:21421
echo.
echo Nacisnij dowolny klawisz, aby zamknac to okno konsoli (boty beda dalej dzialac)...
pause >nul
endlocal
