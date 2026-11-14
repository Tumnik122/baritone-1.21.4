@echo off
setlocal enabledelayedexpansion

echo ======================================================================
echo  Astra Client - Kompilacja modulu natywnego C++ (MSVC 2022 x64)
echo ======================================================================

set "VCVARS_PATH=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

if not exist "!VCVARS_PATH!" (
    echo [BLAD] Nie znaleziono srodowiska MSVC 2022 pod sciezka:
    echo "!VCVARS_PATH!"
    exit /b 1
)

echo [1/3] Inicjalizacja srodowiska kompilatora MSVC 2022 x64...
call "!VCVARS_PATH!"
if errorlevel 1 (
    echo [BLAD] Inicjalizacja vcvars64.bat zwrocila kod bledu!
    exit /b 1
)

cd /d "%~dp0"
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Zulu\zulu-21"

echo [2/3] Kompilacja astra-core.dll (O2, AVX2, fp:fast, C++20, 64-bit DLL, JNI + C ABI)...
cl /LD /O2 /arch:AVX2 /fp:fast /EHsc /std:c++20 /I"!JAVA_HOME!\include" /I"!JAVA_HOME!\include\win32" astra_core.cpp /Fe:astra-core.dll
if errorlevel 1 (
    echo [BLAD] Kompilacja C++ nie powiodla sie!
    exit /b 1
)

echo [3/3] Kopiowanie biblioteki DLL do zasobow moda...
set "TARGET_DIR=..\src\main\resources\assets\astra\native"
if not exist "!TARGET_DIR!" (
    mkdir "!TARGET_DIR!"
)

copy /Y "astra-core.dll" "!TARGET_DIR!\astra-core.dll"
copy /Y "astra-core.dll" "!TARGET_DIR!\astra_core.dll"
copy /Y "astra-core.dll" "astra_core.dll"
if errorlevel 1 (
    echo [BLAD] Kopiowanie pliku astra-core.dll do zasobow nie powiodlo sie!
    exit /b 1
)

echo ======================================================================
echo  [SUKCES] Pomyslnie skompilowano i wdrozeno:
echo  !TARGET_DIR!\astra-core.dll
echo ======================================================================
exit /b 0
