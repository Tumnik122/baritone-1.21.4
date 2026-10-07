# bot-cmd.ps1 — Zaawansowane sterowanie jednym lub wieloma botami Baritone z Windowsa
param(
    [Parameter(Position=0, ValueFromRemainingArguments=$true)]
    [string[]]$CommandArgs,
    [int]$Bot = 1,
    [switch]$All
)

$repoDir = "$PSScriptRoot\baritone-1.21.4"
if (-not (Test-Path "$repoDir\fabric")) {
    $repoDir = $PSScriptRoot
}

# Parsowanie argumentów: obsługa "all", "-all", lub numeru bota jako 1. argumentu
$targetAll = $All.IsPresent
$remainingWords = [System.Collections.Generic.List[string]]::new()

if ($CommandArgs -and $CommandArgs.Count -gt 0) {
    $first = $CommandArgs[0].ToLower().Trim()
    if ($first -eq "-all" -or $first -eq "all" -or $first -eq "--all") {
        $targetAll = $true
        for ($i = 1; $i -lt $CommandArgs.Count; $i++) {
            $remainingWords.Add($CommandArgs[$i])
        }
    } elseif ($first -match '^\d+$') {
        $Bot = [int]$first
        for ($i = 1; $i -lt $CommandArgs.Count; $i++) {
            $remainingWords.Add($CommandArgs[$i])
        }
    } else {
        foreach ($w in $CommandArgs) { $remainingWords.Add($w) }
    }
}

function Get-BotPort([int]$botNum) {
    $p = 21420 + ($botNum - 1)
    # Sprawdz plik dedykowany bot_port_N.txt
    $pf = "$repoDir\bot_port_$botNum.txt"
    if (Test-Path $pf) {
        try {
            $val = [int](Get-Content $pf -Raw).Trim()
            if ($val -ge 21400 -and $val -le 21500) { return $val }
        } catch {}
    }
    return $p
}

function Test-PortOpen([string]$hostStr, [int]$portNum, [int]$timeoutMs = 150) {
    $tcp = New-Object System.Net.Sockets.TcpClient
    try {
        $ar = $tcp.BeginConnect($hostStr, $portNum, $null, $null)
        $success = $ar.AsyncWaitHandle.WaitOne($timeoutMs)
        if ($success) {
            $tcp.EndConnect($ar)
            $tcp.Close()
            return $true
        }
    } catch {}
    $tcp.Close()
    return $false
}

function Get-ActiveBots {
    $active = @()
    for ($i = 1; $i -le 10; $i++) {
        $port = Get-BotPort $i
        if (-not (Test-PortOpen "127.0.0.1" $port 100)) {
            continue
        }
        $url = "http://127.0.0.1:$port"
        try {
            $json = Invoke-RestMethod -Uri "$url/api/status" -Method Get -TimeoutSec 1
            $active += [PSCustomObject]@{
                BotId = $i
                Port  = $port
                Data  = $json
            }
        } catch {}
    }
    return $active
}

function Show-MultiStatus {
    $bots = Get-ActiveBots
    Write-Host ""
    Write-Host "=========================================================================================" -ForegroundColor Cyan
    Write-Host "                           STATUS AKTYWNYCH BOTOW BARITONE                               " -ForegroundColor Yellow
    Write-Host "=========================================================================================" -ForegroundColor Cyan
    if ($bots.Count -eq 0) {
        Write-Host "  [!] Nie wykryto zadnego aktywnego bota (porty 21420+ nie odpowiadaja)." -ForegroundColor Red
        Write-Host "      Uruchom boty poleceniem: .\bot.bat 5  lub  .\start-bots.bat 5" -ForegroundColor Gray
        Write-Host "=========================================================================================" -ForegroundColor Cyan
        return
    }

    $format = "  {0,-5} | {1,-14} | {2,-7} | {3,-10} | {4,-6} | {5,-20} | {6,-12}"
    Write-Host ($format -f "BOT", "NICK", "STAN", "HP", "GLOD", "POZYCJA (X,Y,Z)", "ZADANIE") -ForegroundColor DarkGray
    Write-Host "  ---------------------------------------------------------------------------------------" -ForegroundColor DarkGray

    foreach ($b in $bots) {
        $d = $b.Data
        $onlineStr = if ($d.online) { "ONLINE" } else { "OFFLINE" }
        $color = if ($d.online) { [ConsoleColor]::Green } else { [ConsoleColor]::Red }
        $hpStr = if ($d.online) { "$([Math]::Round($d.health, 1)) / $([Math]::Round($d.maxHealth, 1))" } else { "--" }
        $foodStr = if ($d.online) { "$($d.food) / 20" } else { "--" }
        $posStr = if ($d.online) { "$([Math]::Round($d.x, 1)), $([Math]::Round($d.y, 1)), $([Math]::Round($d.z, 1))" } else { "--" }
        $taskStr = if ($d.online) { $d.activeProcess } else { "Czeka na gracza" }

        Write-Host ("  Bot #{0,-2} | {1,-14} | " -f $b.BotId, $d.name) -NoNewline
        Write-Host ("{0,-7}" -f $onlineStr) -ForegroundColor $color -NoNewline
        Write-Host (" | {0,-10} | {1,-6} | {2,-20} | {3,-12}" -f $hpStr, $foodStr, $posStr, $taskStr)
    }
    Write-Host "=========================================================================================" -ForegroundColor Cyan
    Write-Host ""
}

function Show-SingleStatus([int]$bNum) {
    $port = Get-BotPort $bNum
    $url = "http://127.0.0.1:$port"
    try {
        $status = Invoke-RestMethod -Uri "$url/api/status" -Method Get -TimeoutSec 2
        Write-Host ""
        Write-Host "=== STATUS BOTA #$bNum ($($status.name)) ===" -ForegroundColor Cyan
        if ($status.online) {
            Write-Host " Stan gry:       " -NoNewline; Write-Host "ONLINE" -ForegroundColor Green
            Write-Host " Zdrowie (HP):   " -NoNewline; Write-Host "$($status.health) / $($status.maxHealth)" -ForegroundColor Red
            Write-Host " Glod (Food):    " -NoNewline; Write-Host "$($status.food) / 20" -ForegroundColor Yellow
            Write-Host " Pozycja (X,Y,Z):" -NoNewline; Write-Host "$($status.x), $($status.y), $($status.z)" -ForegroundColor White
            Write-Host " Wymiar:         " -NoNewline; Write-Host "$($status.dimension)" -ForegroundColor Gray
            Write-Host " Aktywne zadanie:" -NoNewline; Write-Host "$($status.activeProcess)" -ForegroundColor Magenta
            Write-Host " Moduly:         " -NoNewline; Write-Host "MobDefense: $($status.mobDefense) | WaterClutch: $($status.waterClutch) | AutoSort: $($status.autoSort) | AutoDrop: $($status.autoDrop)" -ForegroundColor DarkCyan
        } else {
            Write-Host " Stan gry:       " -NoNewline; Write-Host "OFFLINE (Czeka na dolaczenie gracza)" -ForegroundColor Red
        }
        Write-Host " Port HTTP:      $port" -ForegroundColor DarkGray
        Write-Host ""
    } catch {
        Write-Host "[!] Bot #$bNum nie odpowiada na porcie $port." -ForegroundColor Red
    }
}

function Send-CommandToBot([int]$bNum, [string]$cmd) {
    if (-not $cmd -or $cmd.Trim() -eq "") { return }
    $cmd = $cmd.Trim()
    $port = Get-BotPort $bNum
    $url = "http://127.0.0.1:$port"

    if ($cmd -eq "status" -or $cmd -eq "#status") {
        Show-SingleStatus $bNum
        return
    }

    if ($cmd -eq "gui" -or $cmd -eq "#gui" -or $cmd -eq "web") {
        Write-Host "[*] Otwieram wspolny panel floty w przegladarce: http://127.0.0.1:21420" -ForegroundColor Cyan
        Start-Process "http://127.0.0.1:21420"
        return
    }

    $type = "auto"
    if ($cmd.ToLower().StartsWith("chat ") -or $cmd.ToLower().StartsWith("say ")) {
        $cmd = $cmd.Substring($cmd.IndexOf(' ') + 1).Trim()
        $type = "chat"
    } elseif ($cmd.StartsWith("/")) {
        $type = "chat"
    } elseif ($cmd.StartsWith("#")) {
        $type = "baritone"
    }

    try {
        $body = @{ command = $cmd; type = $type } | ConvertTo-Json
        $resp = Invoke-RestMethod -Uri "$url/api/command" -Method Post -Body $body -ContentType "application/json" -TimeoutSec 3
        if ($type -eq "chat") {
            Write-Host "[OK] [Bot #$bNum] Wyslano na czat gry: $cmd" -ForegroundColor Green
        } else {
            Write-Host "[OK] [Bot #$bNum] Wykonano: $cmd" -ForegroundColor Green
        }
    } catch {
        # Fallback: plikowy bufor komend
        $cmdFile = if ($bNum -le 1) { "$repoDir\fabric\run\client\bot_command.txt" } else { "$repoDir\fabric\run\client-$bNum\bot_command.txt" }
        try {
            Set-Content -Path $cmdFile -Value $cmd -Force -Encoding utf8
            Write-Host "[OK] [Bot #$bNum] Zapisano do kolejki plikowej: $cmd" -ForegroundColor Yellow
        } catch {
            Write-Host "[!] [Bot #$bNum] Blad: nie udalo sie wyslac polecenia." -ForegroundColor Red
        }
    }
}

function Send-CommandToAll([string]$cmd) {
    if (-not $cmd -or $cmd.Trim() -eq "") { return }
    $cmd = $cmd.Trim()

    if ($cmd -eq "status" -or $cmd -eq "#status") {
        Show-MultiStatus
        return
    }

    if ($cmd -eq "gui" -or $cmd -eq "#gui" -or $cmd -eq "web") {
        Write-Host "[*] Otwieram wspolny panel floty w przegladarce: http://127.0.0.1:21420" -ForegroundColor Cyan
        Start-Process "http://127.0.0.1:21420"
        return
    }

    Write-Host "[*] Nadawanie do WSZYSTKICH botow: $cmd" -ForegroundColor Cyan
    $active = Get-ActiveBots
    if ($active.Count -eq 0) {
        Write-Host "[!] Zaden bot nie jest aktualnie uruchomiony." -ForegroundColor Red
        return
    }

    foreach ($b in $active) {
        Send-CommandToBot $b.BotId $cmd
    }
}

# --- Wykonanie z wiersza polecen (CLI) ---
if ($remainingWords.Count -gt 0) {
    $fullCmd = ($remainingWords -join " ")
    if ($targetAll) {
        Send-CommandToAll $fullCmd
    } else {
        Send-CommandToBot $Bot $fullCmd
    }
    exit 0
}

# --- Tryb interaktywny konsoli ---
Write-Host ""
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "       BARITONE — KONSOLA STEROWANIA MULTI-BOT            " -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Dostepne polecenia:" -ForegroundColor Gray
Write-Host "    all chat siema       -> pisze 'siema' na czacie serwera (WSZYSTKIE BOTY)" -ForegroundColor Yellow
Write-Host "    all /login haslo     -> logowanie na serwerze (WSZYSTKIE BOTY)" -ForegroundColor Yellow
Write-Host "    all #mine diamond_ore-> kopie diamenty (WSZYSTKIE BOTY)" -ForegroundColor Yellow
Write-Host "    all #stop            -> zatrzymuje WSZYSTKIE boty" -ForegroundColor Yellow
Write-Host "    all #clean / #sort   -> sprzata/sortuje EQ u wszystkich" -ForegroundColor Yellow
Write-Host "    /login haslo         -> komenda serwera dla wybranego bota" -ForegroundColor White
Write-Host "    bot 2                -> przelacza sterowanie na Bota #2" -ForegroundColor White
Write-Host "    status all           -> tabela ze stanem wszystkich botow" -ForegroundColor White
Write-Host "    gui                  -> otwiera JEDNA WSPOLNA STRONE w przegladarce" -ForegroundColor Cyan
Write-Host "    exit                 -> zamyka konsole" -ForegroundColor White
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host ""

Show-MultiStatus

$currentBot = $Bot
while ($true) {
    $inputCmd = Read-Host "[Bot #$currentBot] (lub 'all <cmd>')>"
    if (-not $inputCmd -or $inputCmd.Trim() -eq "") { continue }
    $t = $inputCmd.Trim()

    if ($t.ToLower() -eq "exit" -or $t.ToLower() -eq "quit" -or $t.ToLower() -eq "q") {
        Write-Host "Koniec sesji sterowania." -ForegroundColor Gray
        break
    }

    if ($t.ToLower().StartsWith("bot ") -or $t.ToLower().StartsWith("select ")) {
        $parts = $t -split '\s+'
        if ($parts.Count -ge 2 -and ($parts[1] -match '^\d+$')) {
            $currentBot = [int]$parts[1]
            Write-Host "[*] Przelaczono cel na Bota #$currentBot" -ForegroundColor Cyan
            continue
        }
    }

    if ($t.ToLower() -eq "status all" -or $t.ToLower() -eq "#status all") {
        Show-MultiStatus
        continue
    }

    if ($t.ToLower().StartsWith("all ") -or $t.ToLower().StartsWith("-all ")) {
        $subCmd = $t.Substring($t.IndexOf(' ') + 1).Trim()
        Send-CommandToAll $subCmd
        continue
    }

    if ($t.ToLower() -eq "gui all") {
        Send-CommandToAll "gui"
        continue
    }

    Send-CommandToBot $currentBot $t
}
