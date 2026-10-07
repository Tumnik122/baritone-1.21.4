# java-bot-wrapper.ps1
# Podmienia --gameDir na unikalna sciezke dla kazdego bota
# Wywolywany przez Gradle zamiast java.exe gdy BOT_NUM jest ustawiony

param([Parameter(ValueFromRemainingArguments=$true)][string[]]$JavaArgs)

$realJava = "C:\Program Files\Zulu\zulu-21\bin\java.exe"
$botNum   = $env:BOT_NUM

if (-not $botNum) {
    & $realJava @JavaArgs
    exit $LASTEXITCODE
}

$repo    = $PSScriptRoot
$botDir  = "$repo\fabric\run\client-${botNum}"

# Stworz katalog bota
New-Item -ItemType Directory -Force -Path $botDir | Out-Null

# Skopiuj mods z glownego klienta (pierwszy raz)
$srcMods = "$repo\fabric\run\client\mods"
$dstMods = "$botDir\mods"
if ((Test-Path $srcMods) -and -not (Test-Path $dstMods)) {
    Copy-Item -Path $srcMods -Destination $dstMods -Recurse -Force
}

# Podmien --gameDir w liscie argumentow
$newArgs   = [System.Collections.Generic.List[string]]::new()
$skipNext  = $false

foreach ($arg in $JavaArgs) {
    if ($skipNext) {
        $newArgs.Add($botDir)   # <-- nowa sciezka zamiast starej run\client
        $skipNext = $false
        continue
    }
    if ($arg -eq '--gameDir') {
        $newArgs.Add($arg)
        $skipNext = $true
        continue
    }
    $newArgs.Add($arg)
}

Write-Host "=== BOT #${botNum}: --gameDir => $botDir ===" -ForegroundColor Cyan

& $realJava @newArgs
exit $LASTEXITCODE
