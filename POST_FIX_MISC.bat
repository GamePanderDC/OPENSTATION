@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"
title OpenStation Max — Авторемонт Wi-Fi/BT (подбор и прошивка misc)

echo.
echo ========================================================================
echo   OpenStation Max — Автоматический ремонт Wi-Fi / Bluetooth (misc)
echo   Определяет чип (Intel 0008/00052 или Realtek/Unisoc 00053)
echo   и при необходимости прошивает правильный раздел misc с бекапом.
echo ========================================================================
echo.
set "ST_ADDR="
set /p "ST_ADDR=IP адрес станции для ADB по сети (Enter = автопоиск USB/ADB): "

if "%ST_ADDR%"=="" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\post_fix_misc.ps1"
) else (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\post_fix_misc.ps1" -DeviceSerial "%ST_ADDR%"
)

set "RC=%ERRORLEVEL%"
echo.
if not "%RC%"=="0" (
    echo [ОШИБКА] Код возврата %RC%. Проверьте root ADB и подключение.
) else (
    echo [ГОТОВО] Проверка / авторемонт misc завершены.
)
pause
exit /b %RC%
