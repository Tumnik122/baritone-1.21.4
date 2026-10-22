@echo off
setlocal EnableDelayedExpansion
title Astra Multi-Bot Launcher
chcp 65001 >nul
color 0B

set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

:: Obsluga argumentow wiersza polecen
if not "%~1"=="" (
    if /i "%~1"=="stop" goto :do_stop
    if /i "%~1"=="s" goto :do_stop
    if /i "%~1"=="panel" goto :do_panel
    if /i "%~1"=="p" goto :do_panel
    if /i "%~1"=="1" goto :opt_1
    if /i "%~1"=="2" goto :opt_2
    if /i "%~1"=="add" goto :opt_add
    if /i "%~1"=="a" goto :opt_add
    if /i "%~1"=="q" goto :eof
    for /f "delims=0123456789" %%a in ("%~1") do (
        echo [!] Nieznana opcja: %~1
        pause
        goto :menu
    )
    set "CUSTOM_CNT=%~1"
    goto :run_custom
)

:menu
cls
echo ====================================================================
echo                   ASTRA MULTI-BOT LAUNCHER
echo ====================================================================
echo.

:: Sprawdz dzialajace boty
powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%\start-bots.ps1" -Status

echo.
echo  ------------------------------------------------------------------
echo   [1] 1 BOT         - Uruchom 1 bota (nowy bot lub kolejny wolny)
echo   [2] 2 BOTY        - Uruchom 2 boty (Bot #1 + Bot #2 z osobnymi oknami)
echo   [3] CUSTOM ILOSC  - Wpisz wlasna ilosc botow (np. 3, 4, 5, 10...)
echo  ------------------------------------------------------------------
echo   [A] Dodaj +1 bota - Uruchom nastepnego wolnego bota do istniejacych
echo   [S] Stop          - Zatrzymaj wszystkie uruchomione boty
echo   [P] Panel WWW     - Otworz panel sterowania w przegladarce
echo   [Q] Wyjdz
echo ====================================================================
set "CHOICE="
set /p CHOICE=" Wybierz opcje [1, 2, 3, A, S, P, Q]: "

if "%CHOICE%"=="" set "CHOICE=1"
if /i "%CHOICE%"=="1" goto :opt_1
if /i "%CHOICE%"=="2" goto :opt_2
if /i "%CHOICE%"=="3" goto :opt_custom
if /i "%CHOICE%"=="c" goto :opt_custom
if /i "%CHOICE%"=="a" goto :opt_add
if /i "%CHOICE%"=="s" goto :do_stop
if /i "%CHOICE%"=="p" goto :do_panel
if /i "%CHOICE%"=="q" goto :eof
goto :menu

:opt_1
echo.
echo [*] Uruchamianie 1 bota...
powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%\start-bots.ps1" -Count 1
echo.
pause
goto :menu

:opt_2
echo.
echo [*] Uruchamianie 2 botow (Bot #1 + Bot #2)...
powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%\start-bots.ps1" -Count 2
echo.
pause
goto :menu

:opt_custom
echo.
set "CUSTOM_CNT="
set /p CUSTOM_CNT=" Podaj ile botow chcesz uruchomic (np. 3, 5, 10): "
if "%CUSTOM_CNT%"=="" (
    echo [!] Nie podano liczby.
    timeout /t 2 >nul
    goto :menu
)
for /f "delims=0123456789" %%a in ("!CUSTOM_CNT!") do (
    echo [!] Blad: podaj prawidlowa liczbe calkowita.
    timeout /t 2 >nul
    goto :menu
)

:run_custom
echo.
echo [*] Uruchamianie %CUSTOM_CNT% botow...
powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%\start-bots.ps1" -Count %CUSTOM_CNT%
echo.
pause
goto :menu

:opt_add
echo.
echo [*] Dodawanie kolejnego wolnego bota...
powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%\start-bots.ps1" -Add
echo.
pause
goto :menu

:do_stop
echo.
echo [*] Zatrzymywanie wszystkich aktywnych botow...
if exist "%PROJECT_DIR%\stop-bots.bat" (
    call "%PROJECT_DIR%\stop-bots.bat"
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%\stop-bots.ps1"
)
echo.
pause
goto :menu

:do_panel
echo.
echo [*] Otwieranie glownego panelu sterowania w przegladarce...
start http://localhost:21420
goto :menu
