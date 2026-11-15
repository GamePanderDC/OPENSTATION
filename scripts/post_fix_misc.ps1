<#
.SYNOPSIS
  Автодиагностика и безопасный авто-ремонт раздела misc по ADB (USB или RJ45/Wi-Fi).
  Определяет физический чип Wi-Fi/BT (Intel 0008/00052 vs Realtek/Unisoc 00053)
  и при проблемах с Wi-Fi/BT или несовпадении DTB прошивает правильный 1 MiB образ misc.
#>
[CmdletBinding()]
param(
    [string]$DeviceSerial = "",
    [string]$AdbPath = "",
    [ValidateSet("", "0008", "00052", "0008_00052", "00053")]
    [string]$Revision = "",
    [switch]$CheckOnly,
    [switch]$Force,
    [switch]$NoReboot
)

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot
$PostSh = Join-Path $PSScriptRoot "post_fix_misc.sh"
if (-not (Test-Path $PostSh)) {
    throw "Не найден скрипт: $PostSh"
}

function Resolve-Adb {
    param([string]$Requested)
    if ($Requested -and (Test-Path $Requested)) { return (Resolve-Path $Requested).Path }
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        (Join-Path $RootDir "tools\platform-tools\adb.exe"),
        (Join-Path $RootDir "platform-tools\adb.exe"),
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" }),
        "C:\Android\platform-tools\adb.exe"
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path }
    }
    throw "adb.exe не найден. Укажите -AdbPath или подключите Android Platform Tools."
}

$adb = Resolve-Adb -Requested $AdbPath

$ErrorActionPreference = "Continue"
if ($DeviceSerial) {
    $targetSerial = if ($DeviceSerial -match '^\d+\.\d+\.\d+\.\d+$') { "${DeviceSerial}:5555" } else { $DeviceSerial }
    if ($targetSerial -match ':') {
        & $adb connect $targetSerial 2>$null | Out-Null
    }
    $DeviceSerial = $targetSerial
} else {
    $lines = (& $adb devices 2>$null) -split "\r?\n"
    $ready = @()
    foreach ($ln in $lines) {
        if ($ln -match '^(\S+)\s+device\s*$') {
            $ready += $Matches[1]
        }
    }
    if ($ready.Count -eq 0) {
        throw "Не найдено ни одного подключённого ADB-устройства. Подключите USB или Ethernet (adb connect <IP>:5555)."
    }
    $DeviceSerial = $ready[0]
}
$ErrorActionPreference = "Stop"

Write-Host "=== Авторемонт раздела misc (ADB: $DeviceSerial) ===" -ForegroundColor Cyan

function Invoke-StationShell {
    param([string]$Cmd)
    $out = (& $adb -s $DeviceSerial shell $Cmd 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Ошибка ADB shell ($LASTEXITCODE): $out"
    }
    return ($out | Out-String).Trim()
}

$uidOut = Invoke-StationShell "id -u"
if ($uidOut -ne "0") {
    Write-Host "[!] Adbd запущен без root ($uidOut), пробую adb root..." -ForegroundColor Yellow
    & $adb -s $DeviceSerial root 2>$null | Out-Null
    Start-Sleep -Seconds 2
    $uidOut = Invoke-StationShell "id -u"
    if ($uidOut -ne "0") {
        throw "Требуется root ADB (uid=0), получено uid=$uidOut."
    }
}

$rawSh = [System.IO.File]::ReadAllText($PostSh, [System.Text.UTF8Encoding]::new($false))
$lfSh = $rawSh -replace "`r`n", "`n"
$tmpLf = [System.IO.Path]::GetTempFileName()
try {
    [System.IO.File]::WriteAllText($tmpLf, $lfSh, [System.Text.UTF8Encoding]::new($false))
    $remotePath = "/data/local/openstation/post_fix_misc.sh"
    Invoke-StationShell "mkdir -p /data/local/openstation"
    & $adb -s $DeviceSerial push $tmpLf $remotePath 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Не удалось скопировать post_fix_misc.sh на станцию" }
    Invoke-StationShell "chmod 755 $remotePath"
} finally {
    Remove-Item -Force $tmpLf -ErrorAction SilentlyContinue
}

$checkFlags = @("--check")
if ($Revision) { $checkFlags += "--rev=$Revision" }
if ($Force) { $checkFlags += "--force" }
$checkOut = Invoke-StationShell "sh /data/local/openstation/post_fix_misc.sh $($checkFlags -join ' ')"
Write-Host "[Диагностика] $checkOut" -ForegroundColor Gray

if ($CheckOnly) {
    return
}

$selectedRev = $Revision
$extraForce = $Force
if (-not $selectedRev -and -not $Force) {
    Write-Host ""
    if ($checkOut -match "RESULT=NO_OP") {
        Write-Host "Wi-Fi и Bluetooth сейчас определяются как исправные ($checkOut)." -ForegroundColor Green
    } else {
        Write-Host "У вас не работает Wi-Fi / Bluetooth или не совпадает DTB ревизии!" -ForegroundColor Yellow
    }
    Write-Host "Выберите вашу ревизию станции (написана в конце S/N на дне станции):" -ForegroundColor White
    $hint = if ($checkOut -match "CHIP=intel") { " (авто-подсказка: ревизия 0008 / 00052)" } elseif ($checkOut -match "CHIP=(realtek|unisoc)") { " (авто-подсказка: ревизия 00053)" } else { "" }
    Write-Host "  [1] Ревизия 0008 или 00052 (Intel Wi-Fi/BT -> misc.img)$hint" -ForegroundColor Cyan
    Write-Host "  [2] Ревизия 00053 (Realtek / Unisoc -> misc_00053.img)" -ForegroundColor Cyan
    Write-Host "  [N] Отмена" -ForegroundColor Gray
    $choice = (Read-Host "Введите 1, 2 или N").Trim().ToUpperInvariant()
    switch ($choice) {
        "1" { $selectedRev = "0008_00052"; $extraForce = $true }
        "2" { $selectedRev = "00053"; $extraForce = $true }
        default {
            Write-Host "Отменено пользователем. Раздел misc не изменён." -ForegroundColor Gray
            return
        }
    }
    Write-Host "Сейчас будет выполнена прошивка раздела misc для ревизии $selectedRev..." -ForegroundColor Yellow
} elseif ($selectedRev) {
    $extraForce = $true
}

$runFlags = if ($NoReboot) { @("--apply") } else { @("--auto-reboot") }
if ($selectedRev) { $runFlags += "--rev=$selectedRev" }
if ($extraForce) { $runFlags += "--force" }

$ErrorActionPreference = "Continue"
$applyOut = (& $adb -s $DeviceSerial shell "sh /data/local/openstation/post_fix_misc.sh $($runFlags -join ' ')" 2>&1 | Out-String).Trim()
$applyRc = $LASTEXITCODE
$ErrorActionPreference = "Stop"

Write-Host "[Результат] $applyOut" -ForegroundColor Yellow
if ($applyRc -ne 0 -and $applyOut -notmatch "ACTION=REBOOTING") {
    throw "post_fix_misc.sh завершился с ошибкой ($applyRc): $applyOut"
}

if ($applyOut -match "ACTION=REBOOTING") {
    Write-Host "[*] Станция перезагружается с правильным DTB. Ожидание возврата ADB..." -ForegroundColor Cyan
    Start-Sleep -Seconds 6
    $deadline = (Get-Date).AddSeconds(90)
    $backOnline = $false
    while ((Get-Date) -lt $deadline) {
        $ErrorActionPreference = "Continue"
        if ($DeviceSerial -match ':') {
            & $adb connect $DeviceSerial 2>$null | Out-Null
        }
        $bc = (& $adb -s $DeviceSerial shell "getprop sys.boot_completed" 2>$null | Out-String).Trim()
        $ErrorActionPreference = "Stop"
        if ($bc -eq "1") {
            $backOnline = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if ($backOnline) {
        $postCheck = Invoke-StationShell "sh /data/local/openstation/post_fix_misc.sh --check"
        Write-Host "[После перезагрузки] $postCheck" -ForegroundColor Green
    } else {
        Write-Host "[!] Станция ещё загружается — проверьте Wi-Fi и пульт Bluetooth через ~20 секунд." -ForegroundColor Yellow
    }
}
