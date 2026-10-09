# dev-start.ps1 - Baritone Fabric dev-client launcher (Multi-Bot Safe)
# Usage:
#   .\dev-start.ps1                     # Uruchamia bota (jesli Bot 1 dziala, automatycznie odpala Bota 2 bez ubijania!)
#   .\dev-start.ps1 -InstanceNum 2      # Uruchamia konkretny numer instancji
#   .\dev-start.ps1 -AllJava            # Opcjonalne ubicie procesow Java TYLKO gdy uzytkownik sam to wymusi
param(
    [switch]$AllJava,
    [string]$ClientDir = "",
    [int]$InstanceNum = 0
)

$ErrorActionPreference = "Continue"

$repo = $PSScriptRoot

# --- STEP 1: Process Management (NIE zabijamy dzialajacych procesow!) ---------
Write-Host ""
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "  Baritone dev-client launcher (Multi-Bot Safe)" -ForegroundColor Cyan
Write-Host "=====================================================" -ForegroundColor Cyan

$runningJava = @()
try {
    $runningJava = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue)
} catch {
    Write-Warning "Win32_Process query failed: $_"
}

if ($AllJava) {
    Write-Host "[1/4] Tryb -AllJava aktywny: zamykanie procesow Java..." -ForegroundColor Yellow
    foreach ($p in $runningJava) {
        try {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
            Write-Host "  PID $($p.ProcessId) killed." -ForegroundColor Green
        } catch {}
    }
    Start-Sleep -Milliseconds 1000
} else {
    Write-Host "[1/4] Zachowywanie wszystkich dzialajacych botow i procesow Java (brak zabijania)." -ForegroundColor Green
}

# --- STEP 2: Inteligentne przypisanie numeru instancji (Multi-Bot Auto-Detect) ---
if ($InstanceNum -le 0) {
    $InstanceNum = 1
    # Sprawdz czy Bot #1 (lub inny) juz dziala
    $isBot1Running = $false
    foreach ($jp in $runningJava) {
        $cmd = $jp.CommandLine
        if ($cmd -and ($cmd -match '(?i)knot\.KnotClient' -or $cmd -match '(?i)knot\.Knot\b')) {
            if ($cmd -match '(?i)fabric[\\/]run[\\/]client["\s]' -or $cmd -match '(?i)-DbotNum=1\b') {
                $isBot1Running = $true
                break
            }
        }
    }

    if ($isBot1Running) {
        # Szukamy najnizszego wolnego numeru bota
        $cand = 2
        while ($true) {
            $used = $false
            foreach ($jp in $runningJava) {
                $cmd = $jp.CommandLine
                if ($cmd -and ($cmd -match "(?i)client-${cand}[""\s]" -or $cmd -match "(?i)-DbotNum=${cand}\b")) {
                    $used = $true
                    break
                }
            }
            if (-not $used) {
                $InstanceNum = $cand
                break
            }
            $cand++
        }
        Write-Host "  [*] Bot #1 juz dziala w tle! Automatycznie przelaczam na: BOT #$InstanceNum" -ForegroundColor Cyan
    }
}

if (-not $ClientDir) {
    if ($InstanceNum -le 1) {
        $ClientDir = Join-Path $repo "fabric\run\client"
    } else {
        $ClientDir = Join-Path $repo "fabric\run\client-${InstanceNum}"
    }
}
$processed = Join-Path $ClientDir ".fabric\processedMods"

Write-Host "  Repo     : $repo"
Write-Host "  Instancja: Bot #$InstanceNum"
Write-Host "  Katalog  : $ClientDir"
Write-Host ""

# --- STEP 3: Sprawdzenie cache processedMods dla biezacej instancji -----------
Write-Host "[2/4] Sprawdzanie cache processedMods..." -ForegroundColor Yellow
if (Test-Path -LiteralPath $processed) {
    try {
        Remove-Item -LiteralPath $processed -Recurse -Force -ErrorAction SilentlyContinue
        if (-not (Test-Path -LiteralPath $processed)) {
            Write-Host "  Cache processedMods wyczyszczony." -ForegroundColor Green
        } else {
            Write-Host "  Cache processedMods w uzyciu — kontynuacja bezpieczna." -ForegroundColor Gray
        }
    } catch {
        Write-Host "  Cache processedMods w uzyciu — kontynuacja bezpieczna." -ForegroundColor Gray
    }
} else {
    Write-Host "  Brak starych lockow — czysto." -ForegroundColor Green
}

# --- STEP 4: Sprawdzenie In-Game Account Switcher (IAS) ---------------------
Write-Host ""
Write-Host "[3/4] Sprawdzanie modow (IAS Account Switcher)..." -ForegroundColor Yellow

$modsDir = Join-Path $ClientDir "mods"
if (-not (Test-Path $modsDir)) {
    New-Item -ItemType Directory -Path $modsDir -Force | Out-Null
}

$iasCandidates = @(
    "C:\Users\Administrator\Desktop\IAS-9.0.8+1.21.4-fabric.jar",
    "C:\Users\Administrator\Downloads\IAS-9.0.8+1.21.4-fabric.jar",
    (Join-Path $repo "fabric\run\client\mods\IAS-9.0.8+1.21.4-fabric.jar")
)
$iasDest = Join-Path $modsDir "IAS-9.0.8+1.21.4-fabric.jar"
if (-not (Test-Path $iasDest)) {
    foreach ($cand in $iasCandidates) {
        if ($cand -and (Test-Path $cand)) {
            Copy-Item $cand $iasDest -Force
            Write-Host "  [IAS] In-Game Account Switcher zainstalowany w folderze mods!" -ForegroundColor Green
            break
        }
    }
} else {
    Write-Host "  [IAS] In-Game Account Switcher aktywny w mods." -ForegroundColor Green
}

$fapiInMods = Get-ChildItem -Path $modsDir -Filter "fabric-api-*.jar" -ErrorAction SilentlyContinue
if ($fapiInMods) {
    $fapiInMods | Remove-Item -Force -ErrorAction SilentlyContinue
    Write-Host "  [Fabric API] Usunieto fat-jar z mods (ladowany natywnie przez Gradle)." -ForegroundColor Green
}

# --- STEP 5: Izolowany cache Gradle i start Minecrafta -----------------------
$gradleCacheRoot = "$env:USERPROFILE\.gradle-bots"
if (-not (Test-Path $gradleCacheRoot)) {
    New-Item -ItemType Directory -Path $gradleCacheRoot -Force | Out-Null
}
$botCacheDir = "$gradleCacheRoot\bot-$InstanceNum"
if (-not (Test-Path $botCacheDir)) {
    New-Item -ItemType Directory -Path $botCacheDir -Force | Out-Null
}

$gradleArgs = if ($InstanceNum -le 1) {
    @(":fabric:runClient", "--no-daemon", "--project-cache-dir", $botCacheDir)
} else {
    @(":fabric:runClient", "-PbotNum=${InstanceNum}", "--no-daemon", "--project-cache-dir", $botCacheDir)
}

Write-Host ""
Write-Host "[4/4] Uruchamianie Bota #$InstanceNum: gradlew.bat $($gradleArgs -join ' ')" -ForegroundColor Yellow
Write-Host ""

Set-Location $repo
& (Join-Path $repo "gradlew.bat") @gradleArgs
