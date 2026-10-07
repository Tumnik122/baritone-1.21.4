@echo off
setlocal EnableDelayedExpansion

set "REPO=%~dp0"
if "%REPO:~-1%"=="\" set "REPO=%REPO:~0,-1%"
set "COUNTER_FILE=%REPO%\bot_counter.txt"

:: Obsluga komend specjalnych
if /i "%~1"=="reset" (
    >"%COUNTER_FILE%" echo 1
    echo [OK] Zresetowano licznik botow do 1.
    goto :eof
)

if /i "%~1"=="stop" (
    call "%REPO%\stop-bots.bat"
    goto :eof
)

if /i "%~1"=="gui" (
    call "%REPO%\panel.bat"
    goto :eof
)
if /i "%~1"=="panel" (
    call "%REPO%\panel.bat"
    goto :eof
)
if /i "%~1"=="web" (
    call "%REPO%\panel.bat"
    goto :eof
)

:: Obsluga komendy farm bezposrednio z linii polecen (np. bot.bat farm, bot.bat farm 5)
if /i "%~1"=="farm" goto :cmd_farm
if /i "%~1"=="f" goto :cmd_farm

:: Obsluga komendy login / autologin bezposrednio z linii polecen (np. bot.bat login 5)
if /i "%~1"=="login" goto :cmd_login
if /i "%~1"=="autologin" goto :cmd_login
if /i "%~1"=="l" goto :cmd_login

:: Jezeli podano liczbe botow jako argument (np. bot.bat 1, bot.bat 5, bot.bat 3)
if not "%~1"=="" (
    set "ARG1=%~1"
    for /f "delims=0123456789" %%a in ("!ARG1!") do (
        rem Nie jest czysta liczba
        goto :menu_prompt
    )
    if !ARG1! GEQ 1 (
        echo Uruchamiam !ARG1! botow...
        call "%REPO%\start-bots.bat" !ARG1!
        goto :eof
    )
)

:menu_prompt
:: Odczytaj biezacy licznik
set "BOT_NUM=1"
if exist "%COUNTER_FILE%" (
    set /p BOT_NUM=<"%COUNTER_FILE%"
)
if "!BOT_NUM!"=="" set "BOT_NUM=1"

echo.
echo ==========================================================
echo             BARITONE BOT LAUNCHER
echo ==========================================================
echo  Nastepny pojedynczy bot w kolejce: BOT #!BOT_NUM!
echo.
echo  [1] Uruchom 1 bota (Bot #!BOT_NUM!^)
echo  [5] Uruchom 5 botow naraz (Bot #1 do #5^)
echo  [L] Uruchom z AUTO-LOGINEM Anarchia (/login + serce w kompasie^)
echo  [F] Uruchom bota / boty z zadaniem #farm (Automatyczna farma^)
echo  [N] Wpisz dowolna liczbe botow (np. 3, 10^)
echo  [P] Otworz panel WWW w przegladarce (JEDNA STRONA DLA WSZYSTKICH^)
echo  [S] Zatrzymaj wszystkie uruchomione boty
echo  [R] Zresetuj licznik pojedynczych botow
echo ==========================================================
set "USER_CHOICE=1"
set /p USER_CHOICE=" Wybierz opcje [domyslnie: 1]: "

if "!USER_CHOICE!"=="" set "USER_CHOICE=1"

if /i "!USER_CHOICE!"=="p" (
    call "%REPO%\panel.bat"
    goto :eof
)
if /i "!USER_CHOICE!"=="g" (
    call "%REPO%\panel.bat"
    goto :eof
)
if /i "!USER_CHOICE!"=="gui" (
    call "%REPO%\panel.bat"
    goto :eof
)
if /i "!USER_CHOICE!"=="s" (
    call "%REPO%\stop-bots.bat"
    goto :eof
)
if /i "!USER_CHOICE!"=="r" (
    >"%COUNTER_FILE%" echo 1
    echo [OK] Zresetowano licznik botow do 1.
    goto :eof
)

if /i "!USER_CHOICE!"=="l" goto :login_choice
if /i "!USER_CHOICE!"=="login" goto :login_choice
if /i "!USER_CHOICE!"=="autologin" goto :login_choice

if /i "!USER_CHOICE!"=="f" goto :farm_choice
if /i "!USER_CHOICE!"=="farm" goto :farm_choice

if "!USER_CHOICE!"=="5" (
    call "%REPO%\start-bots.bat" 5
    goto :eof
)

if "!USER_CHOICE!"=="1" (
    call "%REPO%\start-bots.bat" -BotNum !BOT_NUM!
    goto :eof
)

:: Sprawdz czy wpisano liczbe N
for /f "delims=0123456789" %%a in ("!USER_CHOICE!") do (
    echo [!] Nieznana opcja: !USER_CHOICE!
    goto :menu_prompt
)

if !USER_CHOICE! GEQ 1 (
    call "%REPO%\start-bots.bat" !USER_CHOICE!
    goto :eof
)

echo [!] Nieznana opcja.
goto :menu_prompt

:cmd_login
set "LOGIN_COUNT=%~2"
if "!LOGIN_COUNT!"=="" set "LOGIN_COUNT=5"
for /f "delims=0123456789" %%a in ("!LOGIN_COUNT!") do (
    set "LOGIN_COUNT=5"
)
if !LOGIN_COUNT! EQU 1 (
    set "BOT_NUM=1"
    if exist "%COUNTER_FILE%" (
        set /p BOT_NUM=<"%COUNTER_FILE%"
    )
    if "!BOT_NUM!"=="" set "BOT_NUM=1"
    echo Uruchamiam bota #!BOT_NUM! z auto-loginem Anarchia (/login Tumnik@123)...
    call "%REPO%\start-bots.bat" -BotNum !BOT_NUM! -Task autologin
) else (
    echo Uruchamiam !LOGIN_COUNT! botow z auto-loginem Anarchia (/login Tumnik@123)...
    call "%REPO%\start-bots.bat" !LOGIN_COUNT! -Task autologin
)
goto :eof

:login_choice
echo.
echo ==========================================================
echo       URUCHAMIANIE Z AUTO-LOGINEM ANARCHIA.GG
echo    (Direct Connection -^> /login -^> #mine iron -^> #stop -^> Kompas -^> Serce)
echo ==========================================================
echo  [1] Uruchom 1 bota (Bot #!BOT_NUM!^) z auto-loginem
echo  [5] Uruchom 5 botow naraz z auto-loginem
echo  [N] Wpisz dowolna liczbe botow
echo ==========================================================
set "LOGIN_SUB=5"
set /p LOGIN_SUB=" Wybierz ilosc botow [domyslnie: 5]: "
if "!LOGIN_SUB!"=="" set "LOGIN_SUB=5"

if "!LOGIN_SUB!"=="1" (
    call "%REPO%\start-bots.bat" -BotNum !BOT_NUM! -Task autologin
    goto :eof
)
if "!LOGIN_SUB!"=="5" (
    call "%REPO%\start-bots.bat" 5 -Task autologin
    goto :eof
)

for /f "delims=0123456789" %%a in ("!LOGIN_SUB!") do (
    echo [!] Niepoprawna liczba: !LOGIN_SUB!
    goto :login_choice
)

if !LOGIN_SUB! GEQ 1 (
    call "%REPO%\start-bots.bat" !LOGIN_SUB! -Task autologin
    goto :eof
)
goto :menu_prompt

:cmd_farm
set "FARM_COUNT=%~2"
if "!FARM_COUNT!"=="" set "FARM_COUNT=1"
for /f "delims=0123456789" %%a in ("!FARM_COUNT!") do (
    set "FARM_COUNT=1"
)
if !FARM_COUNT! EQU 1 (
    set "BOT_NUM=1"
    if exist "%COUNTER_FILE%" (
        set /p BOT_NUM=<"%COUNTER_FILE%"
    )
    if "!BOT_NUM!"=="" set "BOT_NUM=1"
    echo Uruchamiam bota #!BOT_NUM! z automatycznym zadaniem #farm...
    call "%REPO%\start-bots.bat" -BotNum !BOT_NUM! -Task farm
) else (
    echo Uruchamiam !FARM_COUNT! botow z automatycznym zadaniem #farm...
    call "%REPO%\start-bots.bat" !FARM_COUNT! -Task farm
)
goto :eof

:farm_choice
echo.
echo ==========================================================
echo        URUCHAMIANIE Z AUTOMATYCZNYM ZADANIEM #FARM
echo ==========================================================
echo  [1] Uruchom 1 bota (Bot #!BOT_NUM!^) z zadaniem #farm
echo  [5] Uruchom 5 botow naraz z zadaniem #farm
echo  [N] Wpisz dowolna liczbe botow do farmy
echo ==========================================================
set "FARM_SUB=1"
set /p FARM_SUB=" Wybierz ilosc botow [domyslnie: 1]: "
if "!FARM_SUB!"=="" set "FARM_SUB=1"

if "!FARM_SUB!"=="1" (
    call "%REPO%\start-bots.bat" -BotNum !BOT_NUM! -Task farm
    goto :eof
)
if "!FARM_SUB!"=="5" (
    call "%REPO%\start-bots.bat" 5 -Task farm
    goto :eof
)

for /f "delims=0123456789" %%a in ("!FARM_SUB!") do (
    echo [!] Niepoprawna liczba: !FARM_SUB!
    goto :farm_choice
)

if !FARM_SUB! GEQ 1 (
    call "%REPO%\start-bots.bat" !FARM_SUB! -Task farm
    goto :eof
)
goto :menu_prompt
