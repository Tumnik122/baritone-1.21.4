# start-bots.ps1 — Ultra-Optimized Multi-Bot Direct JVM Launcher (Minecraft 1.21.4 Fabric)
# Direct java.exe launch (0 Gradle Daemons), Java 21 AppCDS, Headless 0-FPS Engine, CPU Affinity & Low Memory Footprint
param(
    [Parameter(Position=0)]
    [int]$Count = 0,

    [int]$BotNum = 0,

    [string]$Account = "",

    [string]$Username = "",

    [int]$RamMb = 0,

    [int]$Port = 21420,

    [ValidateSet("Density", "LowLatency")]
    [string]$Profile = "Density",

    [bool]$HeadlessSecondary = $true,

    [switch]$Visible,

    [switch]$Headless,

    [switch]$SkipBuild,

    [switch]$ForceBuild,

    [switch]$Add,

    [switch]$Status,

    [int]$DelaySeconds = 2,

    [string]$Task = "",

    [switch]$Restart,

    [switch]$Force
)

if ($Restart.IsPresent -or $Force.IsPresent) {
    Write-Host "[*] Opcja -Restart / -Force: Zamykanie istniejacych procesow botow..." -ForegroundColor Magenta
    try {
        $existing = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue)
        foreach ($p in $existing) {
            $cmd = $p.CommandLine
            if ($cmd -match 'client[\\/]+jvm-launch-args' -or $cmd -match 'fabric[\\/]+run[\\/]+client' -or $cmd -match '-D(?:astra\.)?botNum=') {
                Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
            }
        }
        Start-Sleep -Milliseconds 600
    } catch {}
}

if (-not $Account -and $Username) {
    $Account = $Username
}
if (-not $Account) {
    $Account = "Gamer6844"
}

$ErrorActionPreference = "Stop"

$repo = $PSScriptRoot
if (-not (Test-Path "$repo\fabric")) {
    if (Test-Path "$repo\baritone-1.21.4\fabric") {
        $repo = "$repo\baritone-1.21.4"
    }
}

function Get-RunningBotNumbers {
    $running = @()
    try {
        $procs = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue)
        foreach ($p in $procs) {
            $cmd = $p.CommandLine
            if (-not $cmd) { continue }
            if ($cmd -match 'client-(\d+)') {
                $running += [int]$matches[1]
            } elseif ($cmd -match 'client[\\/]+jvm-launch-args' -or $cmd -match 'fabric[\\/]+run[\\/]+client["''\s\\]' -or ($Account -and $cmd -match [regex]::Escape($Account)) -or $cmd -match '--username"?\s+"?Gamer6844') {
                $running += 1
            } elseif ($cmd -match '-D(?:astra\.)?botNum=(\d+)') {
                $running += [int]$matches[1]
            }
        }
    } catch {}
    return ($running | Select-Object -Unique | Sort-Object)
}

if ($Status.IsPresent) {
    $runningBots = @(Get-RunningBotNumbers)
    if ($runningBots.Count -gt 0) {
        Write-Host "  Aktualnie aktywne boty:" -ForegroundColor Green
        $botList = ($runningBots | ForEach-Object { "Bot #$_ (Port: " + (21420 + $_ - 1) + ")" }) -join ", "
        Write-Host "   -> $botList" -ForegroundColor Cyan
    } else {
        Write-Host "  Status: Brak uruchomionych botow." -ForegroundColor DarkGray
    }
    exit 0
}

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   ASTRA MULTI-BOT JVM LAUNCHER (Fabric 1.21.4 / Java 21) " -ForegroundColor Yellow
Write-Host "   Direct JVM Execution * AppCDS * 0-FPS Headless Engine  " -ForegroundColor DarkCyan
Write-Host "==========================================================" -ForegroundColor Cyan

$runningBots = @(Get-RunningBotNumbers)

# Interaktywne zapytanie gdy parametry nie podane
if ($Count -le 0 -and $BotNum -le 0) {
    Write-Host " Ile botow chcesz uruchomic naraz?" -ForegroundColor White
    if ($runningBots.Count -gt 0) {
        Write-Host " (Wykryto dzialajace boty: $($runningBots -join ', '))" -ForegroundColor DarkCyan
    }
    $inputVal = Read-Host " Wpisz liczbe (np. 1, 2, 5, 10) [domyslnie: 1]"
    if (-not $inputVal -or $inputVal.Trim() -eq "") {
        $Count = 1
    } else {
        try {
            $Count = [int]$inputVal.Trim()
        } catch {
            $Count = 1
        }
    }
}

# Inteligentne przydzielanie numeru bota - NIGDY NIE ZAMYKAMY DZIALAJACEGO BOTA!
if ($Add.IsPresent -or ($Count -eq 1 -and $BotNum -le 0)) {
    # Znajdz najnizszy wolny numer bota (jesli Bot 1 dziala -> Bot 2 itd.)
    $cand = 1
    while ($runningBots -contains $cand) {
        $cand++
    }
    $BotNum = $cand
    $Count = 1
    if ($runningBots.Count -gt 0) {
        Write-Host " [*] Wykryto dzialajace boty ($($runningBots -join ', ')). Przydzielam wolny slot: BOT #$BotNum (brak zamykania!)" -ForegroundColor Cyan
    }
} elseif ($BotNum -gt 0 -and ($runningBots -contains $BotNum)) {
    # Jesli podano numer bota ktory juz dziala, automatycznie przelaczamy na kolejny wolny zamiast go zabijac
    $cand = 1
    while ($runningBots -contains $cand) {
        $cand++
    }
    Write-Host " [*] Bot #$BotNum juz jest wlaczony w tle! Automatycznie przelaczam na wolny: BOT #$cand (oba beda dzialac!)" -ForegroundColor Yellow
    $BotNum = $cand
}

$runtimeDir = "$repo\fabric\build\bot-runtime"
$cdsArchive = "$runtimeDir\astra-cds.jsa"
$pidFile = "$runtimeDir\active-bots.json"

# 1. Lokalizacja Java 21 Executable (javaw.exe dla czystego okna GUI bez konsoli)
$javaExe = if (Get-Command "javaw.exe" -ErrorAction SilentlyContinue) {
    "javaw.exe"
} elseif ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javaw.exe")) {
    "$env:JAVA_HOME\bin\javaw.exe"
} else {
    "javaw.exe"
}

# 2. Jednorazowy eksport runtime przez Gradle (jeśli brak plików lub wymuszono ForceBuild)
$cpFile = "$runtimeDir\classpath.txt"
$uniminedArgsFile = "$runtimeDir\unimined-jvm-args.txt"
$mainClassFile = "$runtimeDir\main-class.txt"
$gameArgsFile = "$runtimeDir\game-args.txt"

$runtimeReady = (Test-Path $cpFile) -and (Test-Path $uniminedArgsFile) -and (Test-Path $mainClassFile) -and (Test-Path $gameArgsFile)

if ($ForceBuild -or (-not $runtimeReady -and -not $SkipBuild)) {
    Write-Host "[1/4] Jednorazowy eksport runtime bota (exportBotRuntime)..." -ForegroundColor Cyan
    Push-Location $repo
    try {
        & .\gradlew.bat :fabric:exportBotRuntime
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle exportBotRuntime zakonczyl sie bledem!"
        }
    } finally {
        Pop-Location
    }
} else {
    Write-Host "[1/4] Uzywam gotowego runtime bota (szybki start bez blokad Gradle)..." -ForegroundColor DarkGray
}

$mainClass = (Get-Content $mainClassFile -Raw).Trim()
$gameArgsRaw = (Get-Content $gameArgsFile -Raw).Trim()

# 3. Dynamic AppCDS (Class-Data Sharing) — generowanie przy pierwszym uruchomieniu
if (-not (Test-Path $cdsArchive)) {
    Write-Host "[2/4] Generowanie Java 21 Dynamic AppCDS Archive ($cdsArchive)..." -ForegroundColor Yellow
    $cdsDumpArgs = @(
        "@`"$cpFile`"",
        "-Xms256M", "-Xmx512M",
        "-XX:ArchiveClassesAtExit=`"$cdsArchive`"",
        "--version"
    )
    try {
        Start-Process -FilePath $javaExe -ArgumentList $cdsDumpArgs -NoNewWindow -Wait
        Write-Host "  [+] AppCDS wygenerowany pomyslnie! (Oszczednosc ~180MB RAM na kazdego bota)" -ForegroundColor Green
    } catch {
        Write-Host "  [!] AppCDS dump pominiety: $_" -ForegroundColor DarkGray
    }
}

# 4. CPU Affinity & Konfiguracja wielo-instancyjna
$logicalCores = [Environment]::ProcessorCount
$fullAffinityMask = (1L -shl $logicalCores) - 1

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

$baseClient = "$repo\fabric\run\client"
if (-not (Test-Path $baseClient)) {
    New-Item -ItemType Directory -Path $baseClient -Force | Out-Null
}

function Get-BotProxy([int]$num) {
    $proxyFile = "$repo\proxies.txt"
    if (-not (Test-Path $proxyFile)) {
        return $null
    }
    $lines = @(Get-Content $proxyFile | Where-Object { $_ -and -not $_.Trim().StartsWith("#") })
    if ($lines.Count -eq 0) {
        return $null
    }
    # Indeks proxy: rotacja wg liczby dostępnych proxy
    $idx = ($num - 1) % $lines.Count
    $line = $lines[$idx].Trim()
    if ($line -eq "direct" -or $line -eq "none" -or $line -eq "0") {
        return $null
    }

    $type = "socks5"
    $host = ""
    $port = 1080
    $user = ""
    $pass = ""

    if ($line -match '^(socks5|socks4|http|https)://(?:([^:@]+):([^@]+)@)?([^:]+):(\d+)$') {
        $type = $matches[1]
        $user = $matches[2]
        $pass = $matches[3]
        $host = $matches[4]
        $port = [int]$matches[5]
    } elseif ($line -match '^([^:]+):(\d+):([^:]+):(.+)$') {
        $host = $matches[1]
        $port = [int]$matches[2]
        $user = $matches[3]
        $pass = $matches[4]
    } elseif ($line -match '^([^:]+):(\d+)$') {
        $host = $matches[1]
        $port = [int]$matches[2]
    }

    if ($host) {
        return [PSCustomObject]@{
            Type = $type
            Host = $host
            Port = $port
            User = $user
            Pass = $pass
            Raw  = $line
        }
    }
    return $null
}

function Prepare-BotDirectory([int]$num) {
    $dirName = if ($num -eq 1) { "fabric\run\client" } else { "fabric\run\client-$num" }
    $targetDir = "$repo\$dirName"

    if (-not (Test-Path $targetDir)) {
        New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
        if (Test-Path $baseClient) {
            Get-ChildItem -Path $baseClient | ForEach-Object {
                if ($_.Name -ne ".fabric" -and $_.Name -ne "logs" -and $_.Name -ne "assets") {
                    try {
                        Copy-Item -Path $_.FullName -Destination $targetDir -Recurse -Force -ErrorAction SilentlyContinue
                    } catch {}
                }
            }
        }
    }

    # Zachowaj processedMods - Fabric Loader bezpiecznie uzywa istniejacego remapped cache
    # Usuwanie processedMods podczas pracy innych procesow wywolywalo FileSystemException i zbedne re-mapowanie modow

    # NTFS Junction dla assets (oszczędza dysk i RAM przy wielu botach)
    $primaryAssets = "$baseClient\assets"
    $botAssets = "$targetDir\assets"
    if (($num -gt 1) -and (Test-Path $primaryAssets) -and (-not (Test-Path $botAssets))) {
        try {
            New-Item -ItemType Junction -Path $botAssets -Target $primaryAssets -ErrorAction SilentlyContinue | Out-Null
        } catch {}
    }

    # Upewnij się że options.txt ma optymalne parametry
    $optFile = "$targetDir\options.txt"
    if (Test-Path $optFile) {
        try {
            $optContent = Get-Content $optFile -Raw
            # pauseOnLostFocus:false dla KAŻDEGO bota (nie pauzuje gry gdy gracz klika inne okno!)
            $optContent = $optContent -replace 'pauseOnLostFocus:true', 'pauseOnLostFocus:false'
            $optContent = $optContent -replace 'fullscreen:true', 'fullscreen:false'

            if ($num -eq 1) {
                # Główny bot 1 - płynne 165 FPS, 8 chunków renderu, brak sztucznego dławienia klatek
                $optContent = $optContent -replace 'maxFps:\d+', 'maxFps:165'
                $optContent = $optContent -replace 'renderDistance:\d+', 'renderDistance:8'
                $optContent = $optContent -replace 'simulationDistance:\d+', 'simulationDistance:8'
            } elseif ($num -eq 2 -and ($Count -le 2 -or $Visible.IsPresent)) {
                # Bot 2 z oknem (para 2 botów) - 60 FPS, 6 chunków dla pełnej widoczności
                $optContent = $optContent -replace 'maxFps:\d+', 'maxFps:60'
                $optContent = $optContent -replace 'renderDistance:\d+', 'renderDistance:6'
                $optContent = $optContent -replace 'simulationDistance:\d+', 'simulationDistance:6'
            } else {
                # Boty w tle (3+) - minimalne zużycie zasobów
                $optContent = $optContent -replace 'renderDistance:\d+', 'renderDistance:2'
                $optContent = $optContent -replace 'simulationDistance:\d+', 'simulationDistance:2'
                $optContent = $optContent -replace 'maxFps:\d+', 'maxFps:20'
                $optContent = $optContent -replace 'entityShadows:true', 'entityShadows:false'
                $optContent = $optContent -replace 'ao:true', 'ao:false'
                $optContent = $optContent -replace 'graphicsMode:\d+', 'graphicsMode:0'
            }
            Set-Content -Path $optFile -Value $optContent -Force
        } catch {}
    }

    # Upewnij sie ze bot ma wlasne mapowanie konta w bot_accounts.json
    $accFile = "$targetDir\bot_accounts.json"
    try {
        $accMap = @{}
        if (Test-Path $accFile) {
            $raw = Get-Content $accFile -Raw
            if ($raw -and $raw.Trim().StartsWith("{")) {
                $accMap = $raw | ConvertFrom-Json -AsHashtable
            }
        }
        $bNick = if ($num -eq 1) { $Account } elseif ($num -le $botNames.Count) { $botNames[$num - 1] } else { "Bot_$num" }
        if ($num -gt 1 -and $Account -and $Account -ne "Gamer6844") {
            $bNick = "${Account}_$num"
        }
        if ($excludedNames -contains $bNick) { $bNick = "Grom_$num" }
        $accMap["$num"] = $bNick
        $accMap | ConvertTo-Json -Compress | Set-Content -Path $accFile -Force -Encoding UTF8
    } catch {}

    return $targetDir
}

function Start-DirectBotProcess([int]$num) {
    $basePort = if ($Port -gt 0) { $Port } else { 21420 }
    $port = $basePort + ($num - 1)
    $botNick = if ($num -eq 1) { $Account } elseif ($num -le $botNames.Count) { $botNames[$num - 1] } else { "Bot_$num" }
    if ($num -gt 1 -and $Account -and $Account -ne "Gamer6844") {
        $botNick = "${Account}_$num"
    }
    if ($excludedNames -contains $botNick) {
        $botNick = "Grom_$num"
    }

    $targetDir = Prepare-BotDirectory -num $num

    # Bezpieczny start bota bez zamykania innych instancji
    Write-Host "  [+] Uruchamianie Bota #$num w osobnym srodowisku (Katalog: $targetDir)..." -ForegroundColor DarkCyan

    # Zadanie początkowe jeśli podano
    if ($Task -and $Task.Trim() -ne "") {
        $cleanTask = $Task.Trim()
        if (-not $cleanTask.StartsWith("#") -and -not $cleanTask.StartsWith("/")) {
            $cleanTask = "#$cleanTask"
        }
        $cmdFile = "$targetDir\bot_command.txt"
        try {
            Set-Content -Path $cmdFile -Value $cleanTask -Force -Encoding UTF8
        } catch {}
    }

    # Tryb headless:
    # - Bot 1 ma zawsze widoczne okno 3D.
    # - Jeśli podano -Visible: wszystkie boty mają widoczne okna.
    # - Jeśli podano -Headless: boty 2+ są w tle (headless).
    # - Domyślnie: dla 1-2 botów (Count <= 2) OBA boty mają widoczne okna 3D!
    # - Dla floty 3+ botów: boty 2+ przechodzą w 0-FPS Headless dla oszczędności zasobów.
    $isHeadless = if ($num -eq 1 -or ($Count -le 1 -and $BotNum -gt 0)) {
        "false"
    } elseif ($Visible.IsPresent) {
        "false"
    } elseif ($Headless.IsPresent) {
        "true"
    } elseif ($Count -le 2 -and -not $PSBoundParameters.ContainsKey('HeadlessSecondary')) {
        "false"
    } else {
        $HeadlessSecondary.ToString().ToLower()
    }

    # Flagi GC w zależności od bota i profilu
    $xmxVal = if ($RamMb -ge 512) { "${RamMb}M" } else { "2048M" }
    $xmsVal = if ($RamMb -ge 1024) { "$([int]($RamMb / 2))M" } else { "1024M" }
    $gcFlags = if ($num -eq 1) {
        # Bot 1 (okno gracza): domyslnie 2GB RAM (lub -RamMb), brak ograniczania rdzeni, sub-10ms G1GC dla maksymalnej plynnosci i braku zaciec
        @(
            "-Xms$xmsVal",
            "-Xmx$xmxVal",
            "-XX:MaxMetaspaceSize=256M",
            "-XX:ReservedCodeCacheSize=160M",
            "-XX:MaxDirectMemorySize=256M",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=10",
            "-XX:+UnlockExperimentalVMOptions"
        )
    } elseif ($num -eq 2 -and ($Count -le 2 -or $Visible.IsPresent)) {
        # Bot 2 z oknem (para 2 botów): 1.5GB RAM, 2 rdzenie, sub-15ms GC
        @(
            "-Xms512M",
            "-Xmx1536M",
            "-XX:MaxMetaspaceSize=224M",
            "-XX:ReservedCodeCacheSize=128M",
            "-XX:MaxDirectMemorySize=192M",
            "-XX:ActiveProcessorCount=2",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=15",
            "-XX:+UnlockExperimentalVMOptions"
        )
    } elseif ($Profile -eq "Density") {
        @(
            "-XX:+UnlockExperimentalVMOptions",
            "-Xms384M",
            "-Xmx1024M",
            "-XX:MaxMetaspaceSize=192M",
            "-XX:ReservedCodeCacheSize=96M",
            "-XX:MaxDirectMemorySize=128M",
            "-XX:ActiveProcessorCount=2",
            "-XX:+UseG1GC",
            "-XX:G1HeapRegionSize=1M",
            "-XX:MaxGCPauseMillis=25",
            "-XX:G1NewSizePercent=20",
            "-XX:G1MaxNewSizePercent=40",
            "-XX:InitiatingHeapOccupancyPercent=35",
            "-XX:ParallelGCThreads=2",
            "-XX:ConcGCThreads=1",
            "-XX:+UseStringDeduplication",
            "-XX:+ShrinkHeapInSteps",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=25"
        )
    } else {
        @(
            "-Xms512M",
            "-Xmx1024M",
            "-XX:MaxMetaspaceSize=192M",
            "-XX:ReservedCodeCacheSize=112M",
            "-XX:MaxDirectMemorySize=160M",
            "-XX:ActiveProcessorCount=2",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=15"
        )
    }

    $commonFlags = @(
        "-XX:+DisableExplicitGC",
        "-XX:+UseCompressedOops",
        "-XX:+UseCompressedClassPointers",
        "-DbotNum=$num",
        "-Dastra.botNum=$num",
        "-Dastra.httpPort=$port",
        "-Dastra.headless=$isHeadless",
        "`"-Dfabric.gameDir=$targetDir`""
    )

    $proxy = Get-BotProxy -num $num
    if ($proxy) {
        Write-Host "  [Proxy] Bot #$num -> $($proxy.Type)://$($proxy.Host):$($proxy.Port)" -ForegroundColor Magenta
        if ($proxy.Type -like "socks*") {
            $commonFlags += "-DsocksProxyHost=$($proxy.Host)"
            $commonFlags += "-DsocksProxyPort=$($proxy.Port)"
            if ($proxy.User) {
                $commonFlags += "-Djava.net.socks.username=$($proxy.User)"
                $commonFlags += "-Djava.net.socks.password=$($proxy.Pass)"
            }
        } else {
            $commonFlags += "-Dhttp.proxyHost=$($proxy.Host)"
            $commonFlags += "-Dhttp.proxyPort=$($proxy.Port)"
            $commonFlags += "-Dhttps.proxyHost=$($proxy.Host)"
            $commonFlags += "-Dhttps.proxyPort=$($proxy.Port)"
            if ($proxy.User) {
                $commonFlags += "-Dhttp.proxyUser=$($proxy.User)"
                $commonFlags += "-Dhttp.proxyPassword=$($proxy.Pass)"
                $commonFlags += "-Dhttps.proxyUser=$($proxy.User)"
                $commonFlags += "-Dhttps.proxyPassword=$($proxy.Pass)"
            }
        }
    }

    if ($num -gt 1) {
        $commonFlags += @(
            "-Dio.netty.allocator.numDirectArenas=1",
            "-Dio.netty.allocator.numHeapArenas=1",
            "-Dio.netty.allocator.maxOrder=7",
            "-Dio.netty.allocator.smallCacheSize=64",
            "-Dio.netty.allocator.normalCacheSize=32",
            "-Dio.netty.eventLoopThreads=2",
            "-Djdk.nio.maxCachedBufferSize=65536",
            "-Dastra.disableAudio=true",
            "-Dastra.disableParticles=true"
        )
    } else {
        $commonFlags += @(
            "-Dastra.disableAudio=false",
            "-Dastra.disableParticles=false"
        )
    }

    if (Test-Path $cdsArchive) {
        $commonFlags += "-Xshare:auto"
        $commonFlags += "`"-XX:SharedArchiveFile=$($cdsArchive.Replace('\', '/'))`""
    }

    $botArgFile = "$targetDir\jvm-launch-args.txt"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($botArgFile, [string[]]($gcFlags + $commonFlags), $utf8NoBom)

    # Podmiana username i gameDir w argumentach gry
    $escapedTargetDir = $targetDir.Replace('\', '\\')
    $customGameArgs = $gameArgsRaw -replace '("?--username"?\s+)"[^"]*"', "`$1`"$botNick`""
    $customGameArgs = $customGameArgs -replace '("?--gameDir"?\s+)"[^"]*"', "`$1`"$escapedTargetDir`""

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $javaExe
    $psi.Arguments = "`"@$uniminedArgsFile`" `"@$botArgFile`" `"@$cpFile`" $mainClass $customGameArgs"
    $psi.WorkingDirectory = $targetDir
    $psi.UseShellExecute = $true
    if ($isHeadless -eq "true") {
        $psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Minimized
    } else {
        $psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Normal
    }
    $proc = [System.Diagnostics.Process]::Start($psi)

    # Priorytet i CPU Affinity: Intel Core i5-12400F Core Partitioning (P0-P3 dla Gracza/Bota 1, P4-P5 dla Roju)
    try {
        if ($logicalCores -ge 12) {
            if ($num -gt 1) {
                # Rój botów (2+) izolowany na rdzeniach fizycznych P4-P5 (wątki logiczne 8-11, maska 0xF00 = 3840)
                $proc.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::BelowNormal
                $proc.ProcessorAffinity = [IntPtr]0x0F00
            } else {
                # Główny bot/klient na rdzeniach fizycznych P0-P3 (wątki 0-7, maska 0x0FF = 255) z wyższym priorytetem
                $proc.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::AboveNormal
                $proc.ProcessorAffinity = [IntPtr]0x00FF
            }
        } elseif ($num -gt 1) {
            $proc.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::BelowNormal
            if ($logicalCores -ge 6) {
                $startCore = (($num - 1) * 2) % ($logicalCores - 2)
                $affinityMask = (0x3L -shl $startCore) -band $fullAffinityMask
                if ($affinityMask -gt 0) {
                    $proc.ProcessorAffinity = [IntPtr]$affinityMask
                }
            }
        } else {
            $proc.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::Normal
        }
    } catch {}

    Write-Host ("  [Bot #{0:D2}: {1,-14}] PID: {2,-6} | Port: {3} | Headless: {4}" -f `
        $num, $botNick, $proc.Id, $port, $isHeadless) -ForegroundColor Green

    return [PSCustomObject]@{
        BotNum = $num
        PID    = $proc.Id
        Port   = $port
        Dir    = $targetDir
        Nick   = $botNick
    }
}

# -------------------------------------------------------------
# START
# -------------------------------------------------------------
$activeList = @()

if ($BotNum -gt 0) {
    Write-Host "[3/4] Uruchamianie pojedynczego bota #$BotNum..." -ForegroundColor Cyan
    $b = Start-DirectBotProcess -num $BotNum
    $activeList += $b

    # Zaktualizuj licznik
    $counterFile = "$repo\bot_counter.txt"
    try { Set-Content -Path $counterFile -Value ($BotNum + 1) -Force } catch {}

    Write-Host ""
    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host "  BOT #$BotNum URUCHOMIONY BEZPOSREDNIO PRZEZ JAVA 21!" -ForegroundColor Green
    Write-Host "  Panel WWW: http://localhost:$($b.Port)" -ForegroundColor Cyan
    Write-Host "==========================================================" -ForegroundColor Green
} else {
    Write-Host "[3/4] Uruchamianie $Count botow (Profil: $Profile)..." -ForegroundColor Cyan

    $toLaunch = @()
    for ($i = 1; $i -le $Count; $i++) {
        if ($runningBots -contains $i) {
            $pInfo = $procs | Where-Object {
                $_.CommandLine -match "client-$i[\\/]" -or
                ($i -eq 1 -and ($_.CommandLine -match 'client[\\/]+jvm-launch-args' -or $_.CommandLine -match '--username"?\s+"?Gamer6844')) -or
                $_.CommandLine -match "-D(?:astra\.)?botNum=$i\b"
            } | Select-Object -First 1
            $pidStr = if ($pInfo) { "PID $($pInfo.ProcessId)" } else { "PID aktywny" }
            Write-Host "  [*] Bot #$i juz dziala w tle ($pidStr, proces: javaw.exe, port: $(21420 + $i - 1))." -ForegroundColor Yellow
        } else {
            $toLaunch += $i
        }
    }

    if ($toLaunch.Count -eq 0) {
        Write-Host "  [OK] Wszystkie $Count wybrane boty juz pracuja w tle jako procesy javaw.exe!" -ForegroundColor Green
        Write-Host "       Wskazowka: Aby zrestartowac, kliknij [STOP] w launcherze lub uzyj przelacznika -Restart." -ForegroundColor Cyan
    } else {
        foreach ($num in $toLaunch) {
            $b = Start-DirectBotProcess -num $num
            $activeList += $b
            if ($num -ne $toLaunch[-1] -and $DelaySeconds -gt 0) {
                Start-Sleep -Seconds $DelaySeconds
            }
        }
    }

    # Zaktualizuj licznik
    $counterFile = "$repo\bot_counter.txt"
    try { Set-Content -Path $counterFile -Value ($Count + 1) -Force } catch {}

    Write-Host ""
    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host "  WSZYSTKIE $Count BOTOW URUCHOMIONE (0 PROCESOW GRADLE)!" -ForegroundColor Green
    Write-Host "  Panel Glowny (Bot #1): http://localhost:21420" -ForegroundColor Cyan
    if ($Count -eq 2) {
        Write-Host "  Panel Bota #2:         http://localhost:21421" -ForegroundColor Cyan
        Write-Host "  [TIP] Dwa boty wystartowaly z osobnymi oknami i nickami!" -ForegroundColor Yellow
        Write-Host "        Gdy klikasz w jedno okno, drugie NIE pauzuje (pauseOnLostFocus: false)." -ForegroundColor DarkGray
    }
    Write-Host "==========================================================" -ForegroundColor Green
}

try {
    $mergedBots = @()
    if (Test-Path $pidFile) {
        try {
            $oldJson = Get-Content $pidFile -Raw | ConvertFrom-Json
            if ($oldJson) {
                foreach ($item in @($oldJson)) {
                    if ($item.PID -and (Get-Process -Id $item.PID -ErrorAction SilentlyContinue)) {
                        if (-not ($activeList | Where-Object { $_.BotNum -eq $item.BotNum })) {
                            $mergedBots += $item
                        }
                    }
                }
            }
        } catch {}
    }
    $mergedBots += $activeList
    $mergedBots | ConvertTo-Json | Set-Content -Path $pidFile -Force -Encoding UTF8
} catch {}
