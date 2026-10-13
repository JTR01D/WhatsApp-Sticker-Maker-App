@echo off
chcp 65001 >nul
title Instalador APK - WhatsApp Sticker Maker
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0instalar_apk.ps1"
echo.
pause
