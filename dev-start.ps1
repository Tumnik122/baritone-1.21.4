# dev-start.ps1 - Baritone Fabric dev-client launcher (processedMods fix)
# Usage:
#   .\dev-start.ps1            # smart kill: tylko Gradle daemony, NIE zabija dzialajacych botow
#   .\dev-start.ps1 -AllJava   # nuclear: kill ALL java.exe / javaw.exe
param(
    [switch]$AllJava,
    [string]$ClientDir = "",
    [int]$InstanceNum = 1
)

$ErrorActionPreference = "Continue"

$repo = $PSScriptRoot
if (-not $ClientDir) {
    if ($InstanceNum -le 1) {
        $ClientDir = Join-Path $repo "fabric\run\client"
    } else {
        $ClientDir = Join-Path $repo "fabric\run\client-${InstanceNum}"
    }
}
$processed = Join-Path $ClientDir ".fabric\processedMods"

Write-Host ""
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "  Baritone dev-client launcher (processedMods fix)" -ForegroundColor Cyan
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "  Repo    : $repo"
Write-Host "  Client  : $ClientDir"
Write-Host "  Cache   : $processed"
Write-Host ""

# --- STEP 1: Kill Java processes that may hold processedMods locks ----------
Write-Host "[1/3] Killing Java processes that may lock processedMods..." -ForegroundColor Yellow
Write-Host "  (Tryb multi-bot: dzialajace instancje Minecrafta sa zachowane)" -ForegroundColor Cyan

$clientDirNorm = $ClientDir.Replace("/", "\")
$repoNorm      = $repo.Replace("/", "\")

$allJavaProcs = @()
try {
    $allJavaProcs = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue)
} catch {
    Write-Warning "Win32_Process query failed: $_"
}

$toKill = @()
foreach ($p in $allJavaProcs) {
    if ($AllJava) {
        $toKill += $p
        continue
    }
    $cl = $p.CommandLine
    if (-not $cl) { continue }

    # WAZNE: NIE zabijamy dzialajacych klientow Minecrafta (KnotClient) - tryb multi-bot!
    # Zabijamy tylko: devlaunchinjector (startujace) i Gradle daemony
    if ($cl -match '(?i)knot\.KnotClient')           { continue }  # <-- bot juz dziala, zostaw!
    if ($cl -match '(?i)knot\.Knot\b')               { continue }  # <-- bot juz dziala, zostaw!
    if ($cl -match '(?i)devlaunchinjector')          { $toKill += $p; continue }
    if ($cl -match '(?i)gradle.*daemon')             { $toKill += $p; continue }
    # Procesy z repo w cmdline ktore NIE sa KnotClient (np. zawieszony Gradle)
    if ($cl -match [regex]::Escape($repoNorm) -and $cl -notmatch '(?i)knot\.')  { $toKill += $p; continue }
}

if ($toKill.Count -eq 0) {
    Write-Host "  Brak procesow do ubicia (boty dzialaja dalej)." -ForegroundColor Green
} else {
    Write-Host "  Found $($toKill.Count) process(es) to kill (Gradle/starters only):" -ForegroundColor Red
    foreach ($j in $toKill) {
        $snip = $j.CommandLine
        if ($snip.Length -gt 100) { $snip = $snip.Substring(0, 100) + "..." }
        Write-Host ("  PID {0,6}: {1}" -f $j.ProcessId, $snip)
        try {
            Stop-Process -Id $j.ProcessId -Force -ErrorAction SilentlyContinue
            Write-Host ("  PID {0} killed." -f $j.ProcessId) -ForegroundColor Green
        } catch {
            Write-Host ("  PID {0} kill failed: {1}" -f $j.ProcessId, $_) -ForegroundColor Red
        }
    }
    Write-Host "  Waiting 2s for file handles to release..."
    Start-Sleep -Milliseconds 2000
}

# Also stop Gradle daemons via gradlew --stop
Write-Host "  Stopping Gradle daemons..."
try {
    $gradlew = Join-Path $repo "gradlew.bat"
    & cmd /c "`"$gradlew`" --stop" 2>$null | Out-Null
    Write-Host "  Gradle daemons stopped." -ForegroundColor Green
} catch {
    Write-Host "  Could not stop Gradle daemons (non-fatal)." -ForegroundColor Yellow
}
Start-Sleep -Milliseconds 500

# --- STEP 2: Delete processedMods with multiple fallback strategies ---------
Write-Host ""
Write-Host "[2/3] Clearing processedMods cache..." -ForegroundColor Yellow

if (-not (Test-Path -LiteralPath $processed)) {
    Write-Host "  Not present - nothing to clean." -ForegroundColor Green
} else {
    $deleted = $false

    # Attempt 1: PowerShell Remove-Item
    try {
        Remove-Item -LiteralPath $processed -Recurse -Force -ErrorAction Stop
        Write-Host "  Deleted OK (Remove-Item)." -ForegroundColor Green
        $deleted = $true
    } catch {
        Write-Warning "  Remove-Item failed: $_"
    }

    # Attempt 2: cmd.exe rd /s /q
    if (-not $deleted) {
        Write-Host "  Trying cmd.exe rd /s /q ..." -NoNewline
        cmd /c "rd /s /q `"$processed`""
        if (-not (Test-Path -LiteralPath $processed)) {
            Write-Host " OK" -ForegroundColor Green
            $deleted = $true
        } else {
            Write-Host " still locked" -ForegroundColor Red
        }
    }

    # Attempt 3: robocopy empty-dir mirror trick
    if (-not $deleted) {
        $tmpName = "baritone_empty_" + $PID
        $emptyTmp = Join-Path $env:TEMP $tmpName
        New-Item -ItemType Directory -Path $emptyTmp -Force | Out-Null
        Write-Host "  Trying robocopy empty-mirror trick ..." -NoNewline
        robocopy $emptyTmp $processed /MIR /NFL /NDL /NJH /NJS /nc /ns /np | Out-Null
        Remove-Item -LiteralPath $emptyTmp -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $processed -Recurse -Force -ErrorAction SilentlyContinue
        if (-not (Test-Path -LiteralPath $processed)) {
            Write-Host " OK" -ForegroundColor Green
            $deleted = $true
        } else {
            Write-Host " still locked" -ForegroundColor Red
        }
    }

    if (-not $deleted) {
        Write-Warning "  All deletion attempts failed."
        Write-Warning "  Try: .\dev-start.ps1 -AllJava  to kill ALL java.exe processes."
        Write-Warning "  Or close your IDE and try again."
    }
}

# --- STEP 3: Ensure In-Game Account Switcher (IAS) is installed in mods -----
Write-Host ""
Write-Host "[3/4] Checking mods (In-Game Account Switcher)..." -ForegroundColor Yellow

$modsDir = Join-Path $ClientDir "mods"
if (-not (Test-Path $modsDir)) {
    New-Item -ItemType Directory -Path $modsDir -Force | Out-Null
}

$iasCandidates = @(
    "C:\Users\Administrator\Desktop\IAS-9.0.8+1.21.4-fabric.jar",
    "C:\Users\Administrator\Downloads\IAS-9.0.8+1.21.4-fabric.jar"
)
$iasDest = Join-Path $modsDir "IAS-9.0.8+1.21.4-fabric.jar"
if (-not (Test-Path $iasDest)) {
    foreach ($cand in $iasCandidates) {
        if (Test-Path $cand) {
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
    Write-Host "  [Fabric API] Usunieto fat-jar z mods (Fabric API ladowany jest natywnie i re-mapowany przez Gradle)." -ForegroundColor Green
}

# --- STEP 4: Launch Minecraft client ----------------------------------------
Write-Host ""
Write-Host "[4/4] Launching: gradlew.bat :fabric:runClient${InstanceNum}" -ForegroundColor Yellow
Write-Host ""

$gradleArgs = if ($InstanceNum -le 1) {
    @(":fabric:runClient")
} else {
    @(":fabric:runClient", "-PbotNum=${InstanceNum}")
}

Write-Host "[4/4] Launching: gradlew.bat $($gradleArgs -join ' ')" -ForegroundColor Yellow
Write-Host ""

Set-Location $repo
& (Join-Path $repo "gradlew.bat") @gradleArgs

