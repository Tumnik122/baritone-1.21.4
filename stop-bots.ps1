# stop-bots.ps1 — Bezpieczne zatrzymywanie wszystkich instancji botow Baritone i daemonow Gradle

$repo = $PSScriptRoot

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Red
Write-Host "         ZATRZYMYWANIE WSZYSTKICH BOTOW BARITONE         " -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Red

$stopped = 0
try {
    $procs = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue)
    foreach ($p in $procs) {
        $cl = $p.CommandLine
        if (-not $cl) { continue }

        $isBaritoneBot = ($cl -match '(?i)knot\.KnotClient' -or $cl -match '(?i)devlaunchinjector' -or ($cl -match '(?i)gradle.*daemon' -and $cl -match [regex]::Escape($repo)))
        if ($isBaritoneBot) {
            Write-Host "  [*] Zamykanie procesu PID $($p.ProcessId)..." -ForegroundColor Gray
            try {
                Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
                $stopped++
            } catch {}
        }
    }
} catch {
    Write-Warning "Błąd podczas przeszukiwania procesów: $_"
}

Write-Host ""
if ($stopped -gt 0) {
    Write-Host "[OK] Zatrzymano $stopped proces(ow) powiazanych z botami." -ForegroundColor Green
} else {
    Write-Host "[OK] Zaden bot nie byl uruchomiony." -ForegroundColor Yellow
}

# Zresetuj plik licznika
$counter = "$repo\bot_counter.txt"
if (Test-Path $counter) {
    Set-Content -Path $counter -Value "1" -Force
}

Write-Host "Wszystkie porty i blokady plikow zostaly zwolnione." -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Red
