# start-bots.ps1 — Zaawansowany Multi-Bot Launcher dla Baritone Fabric
# Uruchamia dowolna liczbe niezaleznych botow (np. 1 lub 5) w izolowanych srodowiskach

param(
    [Parameter(Position=0)]
    [int]$Count = 0,

    [int]$BotNum = 0,

    [int]$DelaySeconds = 3,

    [string]$Task = ""
)

Add-Type -AssemblyName System.IO.Compression.FileSystem

$repo = $PSScriptRoot
if (-not (Test-Path "$repo\fabric")) {
    if (Test-Path "$repo\baritone-1.21.4\fabric") {
        $repo = "$repo\baritone-1.21.4"
    }
}

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "       BARITONE MULTI-BOT LAUNCHER (Fabric 1.21.4)        " -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan

# Jezeli nie podano liczby botow ani numeru pojedynczego bota, zapytaj:
if ($Count -le 0 -and $BotNum -le 0) {
    Write-Host " Ile botow chcesz uruchomic naraz?" -ForegroundColor White
    $inputVal = Read-Host " Wpisz liczbe (np. 1, 3, 5, 10) [domyslnie: 5]"
    if (-not $inputVal -or $inputVal.Trim() -eq "") {
        $Count = 5
    } else {
        try {
            $Count = [int]$inputVal.Trim()
        } catch {
            $Count = 5
        }
    }
}

if ($Count -eq 1 -and $BotNum -le 0) {
    $BotNum = 1
}

if ($Count -gt 30) {
    Write-Host "[!] Maksymalna zalecana liczba botow to 30. Ustawiam 30." -ForegroundColor Yellow
    $Count = 30
}

$baseClient = "$repo\fabric\run\client"
if (-not (Test-Path $baseClient)) {
    New-Item -ItemType Directory -Path $baseClient -Force | Out-Null
}

# Sprawdz In-Game Account Switcher w glownym kliencie
$iasCandidates = @(
    "C:\Users\Administrator\Desktop\IAS-9.0.8+1.21.4-fabric.jar",
    "C:\Users\Administrator\Downloads\IAS-9.0.8+1.21.4-fabric.jar"
)
$modsDir = "$baseClient\mods"
if (-not (Test-Path $modsDir)) {
    New-Item -ItemType Directory -Path $modsDir -Force | Out-Null
}
$iasDest = "$modsDir\IAS-9.0.8+1.21.4-fabric.jar"
if (-not (Test-Path $iasDest)) {
    foreach ($cand in $iasCandidates) {
        if (Test-Path $cand) {
            Copy-Item $cand $iasDest -Force
            Write-Host "  [+] Zainstalowano In-Game Account Switcher w mods bazy." -ForegroundColor DarkCyan
            break
        }
    }
}

# Usun ewentualne stare locki gradle/devlaunchinjector z poprzednich zacietych sesji
try {
    $procs = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue)
    foreach ($p in $procs) {
        $cl = $p.CommandLine
        if ($cl -and ($cl -match '(?i)devlaunchinjector' -or ($cl -match '(?i)gradle.*daemon' -and $cl -match [regex]::Escape($repo)))) {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
} catch {}

# Kazdy bot dostaje wlasny project-cache-dir zeby uniknac Gradle lock contention
# WAZNE: sciezka bez spacji (inaczej Gradle zle parsuje argumenty)
$gradleCacheRoot = "$env:USERPROFILE\.gradle-bots"
if (-not (Test-Path $gradleCacheRoot)) {
    New-Item -ItemType Directory -Path $gradleCacheRoot -Force | Out-Null
}

# -----------------------------------------------------------------------
# PRE-WARM: Upewnij sie ze unimined mappings i assets sa gotowe
# intermediary2named.jar musi istniec zanim Fabric Loader uruchomi boty
# -----------------------------------------------------------------------
$mappingsJar = "$repo\fabric\.gradle\unimined\local\mappings\intermediary2named.jar"
$nativesFile = "$repo\fabric\run\client\natives\glfw.dll"
$mappingsOk = $false
if (Test-Path $mappingsJar) {
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($mappingsJar)
        $zip.Dispose()
        $mappingsOk = $true
    } catch {}
}
$nativesOk = (Test-Path $nativesFile) -and ((Get-Item $nativesFile).Length -gt 100000)

if (-not $mappingsOk -or -not $nativesOk) {
    Write-Host ""
    Write-Host "[PRE-WARM] Przygotowywanie mapowan Unimined, bibliotek natywnych (DLL) i assets..." -ForegroundColor Yellow
    Write-Host "[PRE-WARM] Generowanie przez Gradle (raz, ~30s)..." -ForegroundColor Yellow
    $preCacheDir = "$gradleCacheRoot\pre-warm"
    if (-not (Test-Path $preCacheDir)) { New-Item -ItemType Directory -Path $preCacheDir -Force | Out-Null }
    & "$repo\gradlew.bat" --no-daemon "--project-cache-dir=$preCacheDir" :fabric:preRunClient 2>&1 | ForEach-Object {
        if ($_ -match "intermediary|mappings|Remapping|BUILD|Exception|FAILURE") {
            Write-Host "  $_" -ForegroundColor DarkCyan
        }
    }
    if ((Test-Path $mappingsJar) -and (Test-Path $nativesFile)) {
        Write-Host "[PRE-WARM] Mappings, DLL-ki i assets w pelni gotowe!" -ForegroundColor Green
    } else {
        Write-Host "[PRE-WARM] UWAGA: Sprawdz stan plikow w fabric/run/client/natives!" -ForegroundColor Red
    }
    Write-Host ""
}

$excludedNames = @("Mroz_53947", "Kielbasa_1911")

$botNames = @(
    "Gamer6844", "MocnyZubr80", "Grom_91244", "Krysztal_7821", "ZlotyZabojca145",
    "SzmaragdowyDuch6", "WilkPlays7497", "Kruk_35789", "SlayerKox0226", "ZabojcaVIP9427",
    "StalowySzkielet0", "BratMC4061", "Zuk_23540", "RybaTop737", "JablkoHD243",
    "Ser8092", "MistrzKotel616", "SmoczyCien715", "Dynia2616", "Ptak4417",
    "SlonecznyWilk34", "Piekny_Cien563", "CebulaXD664", "Burak25350", "Ciastek12110",
    "Bulka93513", "StormJagoda707", "Hunter43694", "Burak42142", "Chomik0999",
    "Krolik98433", "KrolewskiZiomecz", "Rycerz_0287", "Wariat38336", "Blaze08173",
    "PanMisiek07", "Rubinowy_Ninja77", "DyniaPRO1053", "Kotel_43802", "Ghul70968",
    "Sasiad91126", "Aniol9711", "GhostKotlet09", "KartofelVIP446", "Ciastek05829"
)

function Prepare-BotDirectory([int]$num) {
    $targetDir = if ($num -eq 1) { "$repo\fabric\run\client" } else { "$repo\fabric\run\client-$num" }
    if (-not (Test-Path $targetDir)) {
        Write-Host "  [*] Tworzenie katalogu dla Bota #$num ($targetDir)..." -ForegroundColor Gray
        New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
        if (Test-Path $baseClient) {
            Get-ChildItem -Path $baseClient | ForEach-Object {
                if ($_.Name -ne ".fabric" -and $_.Name -ne "logs") {
                    Copy-Item -Path $_.FullName -Destination $targetDir -Recurse -Force
                }
            }
        }
    } else {
        # Upewnij sie ze mods ma IAS mod
        $targetMods = "$targetDir\mods"
        if (-not (Test-Path $targetMods)) { New-Item -ItemType Directory -Path $targetMods -Force | Out-Null }
        if (Test-Path $iasDest) {
            $tIas = "$targetMods\IAS-9.0.8+1.21.4-fabric.jar"
            if (-not (Test-Path $tIas)) {
                Copy-Item $iasDest $tIas -Force
            }
        }
    }

    # Wyczysc potencjalnie uszkodzony cache processedMods (zapobiega NullPointerException w RuntimeModRemapper)
    $processed = "$targetDir\.fabric\processedMods"
    if (Test-Path $processed) {
        try {
            Remove-Item -LiteralPath $processed -Recurse -Force -ErrorAction SilentlyContinue
        } catch {}
    }
}

function Wait-BotPortReady([int]$port, [int]$maxWaitSeconds = 60) {
    $ready = $false
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $maxWaitSeconds) {
        Start-Sleep -Milliseconds 1500
        try {
            $tcp = [System.Net.Sockets.TcpClient]::new()
            $async = $tcp.BeginConnect("127.0.0.1", $port, $null, $null)
            $success = $async.AsyncWaitHandle.WaitOne(600, $false)
            if ($success -and $tcp.Connected) {
                $tcp.EndConnect($async)
                $tcp.Close()
                $ready = $true
                break
            }
            $tcp.Close()
        } catch {}
        Write-Host "." -NoNewline -ForegroundColor DarkGray
    }
    return $ready
}

function Start-BotProcess([int]$num) {
    $port = 21420 + ($num - 1)
    $dirName = if ($num -eq 1) { "fabric\run\client" } else { "fabric\run\client-$num" }
    $botNick = if ($num -le $botNames.Count) { $botNames[$num - 1] } else { "Bot_$num" }
    if ($excludedNames -contains $botNick) {
        Write-Host "  [!] BLOKADA: Nick $botNick jest na czarnej liście wykluczonych kont! Zmieniam na zapasowy..." -ForegroundColor Red
        $botNick = "Grom_$num"
    }
    $targetDir = "$repo\$dirName"

    Prepare-BotDirectory -num $num

    if ($Task -and $Task.Trim() -ne "") {
        $cleanTask = $Task.Trim()
        if (-not $cleanTask.StartsWith("#") -and -not $cleanTask.StartsWith("/")) {
            $cleanTask = "#$cleanTask"
        }
        # Jesli uruchamiamy wiecej niz 1 bota z auto-loginem, boty 2+ czekaja na centralna kolejke z Bota #1 (po 20s po zalogowaniu kazdego)
        if ($Count -gt 1 -and ($cleanTask -match '(?i)autologin|anarchia') -and $num -gt 1) {
            Write-Host "  [*] Bot #${num}: oczekuje na sekwencyjny Auto-Login z centrali floty (kolejno po 20s)" -ForegroundColor DarkCyan
        } else {
            $cmdFile = "$targetDir\bot_command.txt"
            try {
                Set-Content -Path $cmdFile -Value $cleanTask -Force -Encoding UTF8
                Write-Host "  [*] Zadanie startowe dla Bota #${num}: $cleanTask" -ForegroundColor Green
            } catch {}
        }
    }

    $botCacheDir = "$gradleCacheRoot\bot-$num"
    if (-not (Test-Path $botCacheDir)) {
        New-Item -ItemType Directory -Path $botCacheDir -Force | Out-Null
    }

    Write-Host " [Bot #${num}: $botNick] Startuje..." -ForegroundColor Yellow -NoNewline
    Write-Host " Dir: $dirName | WWW: http://localhost:$port" -ForegroundColor White

    $repoEscaped = $repo -replace "'", "''"
    $gradleArgs = @(
        '-NoExit', '-Command',
        "Set-Location '$repoEscaped'; .\gradlew.bat :fabric:runClient -PbotNum=${num} --no-daemon --project-cache-dir '$botCacheDir'"
    )
    Start-Process powershell -ArgumentList $gradleArgs -WindowStyle Normal
}

# -------------------------------------------------------------
# SCENARIUSZ 1: Uruchomienie pojedynczego bota (-BotNum)
# -------------------------------------------------------------
if ($BotNum -gt 0) {
    Write-Host "=== URUCHAMIANIE POJEDYNCZEGO BOTA #$BotNum ===" -ForegroundColor Cyan
    Start-BotProcess -num $BotNum
    $port = 21420 + ($BotNum - 1)
    $nextNum = $BotNum + 1

    # Zaktualizuj plik licznika
    $counterFile = "$repo\bot_counter.txt"
    try {
        Set-Content -Path $counterFile -Value "$nextNum" -Force
    } catch {}

    Write-Host ""
    Write-Host "  [*] Oczekiwanie na uruchomienie bota i start panelu WWW (port $port)..." -ForegroundColor DarkCyan -NoNewline
    $ready = Wait-BotPortReady -port $port -maxWaitSeconds 60
    if ($ready) {
        Write-Host " [GOTOWY]!" -ForegroundColor Green
        Start-Process "http://127.0.0.1:$port"
    } else {
        Write-Host " [W tle]" -ForegroundColor Yellow
    }

    Write-Host ""
    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host "  BOT #$BotNum ZOSTAŁ URUCHOMIONY!" -ForegroundColor Green
    Write-Host "  Panel WWW: http://localhost:$port" -ForegroundColor Cyan
    Write-Host "  Nastepny w kolejce: Bot #$nextNum" -ForegroundColor Gray
    Write-Host "==========================================================" -ForegroundColor Green
    exit 0
}

# -------------------------------------------------------------
# SCENARIUSZ 2: Uruchomienie wielu botow naraz (np. 5 botow)
# -------------------------------------------------------------
Write-Host "=== ROZPOCZYNAM SEKWENCYJNE URUCHAMIANIE $Count BOTOW ===" -ForegroundColor Cyan
Write-Host "(Inteligentne oczekiwanie na gotowosc instancji przed startem kolejnej)" -ForegroundColor Gray
Write-Host ""

for ($i = 1; $i -le $Count; $i++) {
    Start-BotProcess -num $i
    $port = 21420 + ($i - 1)

    if ($i -lt $Count) {
        Write-Host "         [*] Oczekiwanie na gotowosc Bota #$i (port $port)..." -ForegroundColor DarkCyan -NoNewline
        $isReady = Wait-BotPortReady -port $port -maxWaitSeconds 60
        if ($isReady) {
            Write-Host " [GOTOWY]!" -ForegroundColor Green
            if ($i -eq 1) {
                Start-Process "http://127.0.0.1:$port"
            }
            Start-Sleep -Seconds 2
        } else {
            Write-Host " [OK / kontynuacja]" -ForegroundColor Yellow
            Start-Sleep -Seconds $DelaySeconds
        }
    }
}

# Zaktualizuj plik licznika na kolejnego wolnego bota
$nextAfterMulti = $Count + 1
$counterFile = "$repo\bot_counter.txt"
try {
    Set-Content -Path $counterFile -Value "$nextAfterMulti" -Force
} catch {}

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "  WSZYSTKIE $Count BOTOW ZOSTALY URUCHOMIONE!" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host ""
Write-Host " Dostepne panele WWW w przegladarce:" -ForegroundColor Cyan
for ($i = 1; $i -le $Count; $i++) {
    $p = 21420 + ($i - 1)
    Write-Host "   Bot #${i}:  http://localhost:$p" -ForegroundColor White
}
Write-Host ""
Write-Host " Szybkie sterowanie wszystkimi botami naraz:" -ForegroundColor Yellow
Write-Host "   .\bot-control.bat -all #farm" -ForegroundColor White
Write-Host "   .\bot-control.bat -all #mine diamond_ore" -ForegroundColor White
Write-Host "   .\bot-control.bat -all #defend" -ForegroundColor White
Write-Host "   .\bot-control.bat -all #clean" -ForegroundColor White
Write-Host "   .\bot-control.bat -all #stop" -ForegroundColor White
Write-Host "   .\bot-control.bat -all status" -ForegroundColor White
Write-Host ""
if ($Task -and $Task.Trim() -ne "") {
    Write-Host "  Automatyczne zadanie przy dolaczeniu do gry: $Task" -ForegroundColor Green
    Write-Host ""
}
if ($Count -gt 1 -and $Task -and ($Task -match '(?i)autologin|anarchia')) {
    Write-Host "  [*] Aktywowanie sekwencyjnego Auto-Loginu dla floty $Count botow..." -ForegroundColor Yellow
    try {
        $loginBody = '{"targets":[0],"command":"autologin Tumnik@123","type":"auto"}'
        Invoke-RestMethod -Uri "http://127.0.0.1:21420/api/fleet/command" -Method Post -Body $loginBody -ContentType "application/json" -TimeoutSec 5 | Out-Null
        Write-Host "  [+] Sekwencja floty wlaczona: boty zaloguja sie jeden po drugim (kolejny 20s po zalogowaniu poprzednika)!" -ForegroundColor Green
        Write-Host ""
    } catch {
        Write-Host "  [!] Otworz panel WWW (http://localhost:21420) i kliknij przycisk AUTO-LOGIN ANARCHIA." -ForegroundColor Yellow
    }
}
Write-Host " Aby zamknac wszystkie boty: uruchom stop-bots.bat" -ForegroundColor Gray
Write-Host "==========================================================" -ForegroundColor Green
