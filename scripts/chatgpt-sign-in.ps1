param(
    [string]$Station,
    [ValidateRange(1, 65535)]
    [int]$WebPort = 8080
)
$ErrorActionPreference = "Stop"
$adb = Get-Command adb.exe -ErrorAction SilentlyContinue
if (-not $adb) {
    $path = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Установите Android SDK Platform-Tools с developer.android.com/tools/releases/platform-tools."
    }
    $adbPath = $path
} else {
    $adbPath = $adb.Source
}

if ($Station) {
    if ($Station -notmatch '^[a-zA-Z0-9][a-zA-Z0-9.-]*(:[0-9]{1,5})?$') {
        throw "Укажите адрес станции, например station-max.local:5555."
    }
    if ($Station -notmatch ':') { $Station = "$($Station):5555" }
    & $adbPath connect $Station
    if ($LASTEXITCODE -ne 0) { throw "Не удалось подключить станцию." }
} else {
    $devices = @(& $adbPath devices | ForEach-Object {
        if ($_ -match '^(\S+)\s+device$') { $Matches[1] }
    })
    if ($devices.Count -ne 1) {
        throw "Подключите одну станцию через ADB или запустите с параметром -Station."
    }
    $Station = $devices[0]
}
$deviceState = & $adbPath -s $Station get-state
if ($LASTEXITCODE -ne 0 -or "$deviceState".Trim() -ne "device") {
    throw "Станция не подключена. Проверьте ADB и разрешение на подключение."
}

$callbackReady = $false
$panelPort = ""
try {
    & $adbPath -s $Station forward --no-rebind tcp:1455 tcp:1455
    if ($LASTEXITCODE -ne 0) { throw "Порт 1455 занят. Закройте другой помощник входа и попробуйте снова." }
    $callbackReady = $true

    $panelPort = "$(& $adbPath -s $Station forward tcp:0 "tcp:$WebPort")".Trim()
    if ($LASTEXITCODE -ne 0 -or $panelPort -notmatch '^[0-9]{1,5}$') {
        throw "Не удалось открыть настройки станции."
    }
    $panel = "http://127.0.0.1:$panelPort"
    try {
        Invoke-RestMethod -Uri "$panel/api/chatgpt/status" -TimeoutSec 5 | Out-Null
    } catch {
        throw "На станции нет поддержки ChatGPT или указан неверный порт веб-панели."
    }
    Start-Process "$panel/?chatgpt=1"
    Write-Host "Завершите вход и выберите ChatGPT в браузере. После этого нажмите Enter."
    Read-Host | Out-Null
} finally {
    if ($panelPort -match '^[0-9]{1,5}$') {
        & $adbPath -s $Station forward --remove "tcp:$panelPort"
    }
    if ($callbackReady) {
        & $adbPath -s $Station forward --remove tcp:1455
    }
}
