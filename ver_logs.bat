@echo off
title Logs en tiempo real - WhatsApp Sticker Maker
echo ========================================================
echo   Mostrando logs en tiempo real de WhatsApp y StickerMaker...
echo   Presiona Ctrl + C para detener.
echo ========================================================
"C:\Users\juane\Downloads\platform-tools\adb.exe" logcat -c
"C:\Users\juane\Downloads\platform-tools\adb.exe" logcat -v time | findstr /i "stickermaker whatsapp StickerContentProvider AddThirdPartyStickerPackActivity StickerPackValidator"
