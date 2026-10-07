@echo off
setlocal EnableDelayedExpansion

set "REPO=%~dp0"
if "%REPO:~-1%"=="\" set "REPO=%REPO:~0,-1%"

echo ==========================================================
echo        BARITONE WEB DASHBOARD (Flota Botow)
echo ==========================================================

:: Sprawdz ktory port z zakresu 21420-21430 nasluchuje
set "ACTIVE_PORT="
for /L %%P in (21420,1,21430) do (
    if "!ACTIVE_PORT!"=="" (
        netstat -ano | findstr /R /C:":%%P .*LISTENING" >nul 2>&1
        if !ERRORLEVEL! EQU 0 (
            set "ACTIVE_PORT=%%P"
        )
    )
)

if not "!ACTIVE_PORT!"=="" (
    echo [OK] Wykryto aktywnego bota na porcie !ACTIVE_PORT!!
    echo Otwieram panel w przegladarce: http://127.0.0.1:!ACTIVE_PORT!
    start http://127.0.0.1:!ACTIVE_PORT!
    goto :eof
)

echo [!] Zaden bot nie jest obecnie wlaczony (porty 21420-21430 sa zamkniete).
echo     Strona WWW jest hostowana wewnatrz procesu gry uruchomionego bota.
echo.
set /p START_BOT="Czy chcesz uruchomic boty teraz? [5 / 1 / N, domyslnie 5]: "
if "!START_BOT!"=="" set START_BOT=5
if /i "!START_BOT!"=="n" goto :eof

if "!START_BOT!"=="1" (
    call "%REPO%\start-bots.bat" 1
) else if "!START_BOT!"=="5" (
    call "%REPO%\start-bots.bat" 5
) else (
    call "%REPO%\start-bots.bat" !START_BOT!
)
