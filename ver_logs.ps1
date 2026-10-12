$Host.UI.RawUI.WindowTitle = "Logs en tiempo real - WhatsApp Sticker Maker"

Clear-Host
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "  Mostrando logs en tiempo real de WhatsApp y StickerMaker" -ForegroundColor Green
Write-Host "  Presiona Ctrl + C para detener." -ForegroundColor Yellow
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host ""

$adb = "C:\Users\juane\Downloads\platform-tools\adb.exe"

if (-not (Test-Path $adb)) {
    Write-Host "Error: No se encontró adb.exe en $adb" -ForegroundColor Red
    pause
    exit
}

# Limpiar buffer anterior de logcat
Write-Host "Limpiando buffer de logcat previo..." -ForegroundColor DarkGray
& $adb logcat -c

Write-Host "Esperando eventos del telefono..." -ForegroundColor Cyan
Write-Host ""

$pattern = "stickermaker|whatsapp|StickerContentProvider|AddThirdPartyStickerPackActivity|StickerPackValidator"

# Transmisión en tiempo real con resaltado de sintaxis
& $adb logcat -v time | ForEach-Object {
    if ($_ -match $pattern) {
        if ($_ -match " E/|Exception|Error|fail|FATAL") {
            Write-Host $_ -ForegroundColor Red
        } elseif ($_ -match " W/|Warn") {
            Write-Host $_ -ForegroundColor Yellow
        } elseif ($_ -match "StickerContentProvider|WhatsAppChecker") {
            Write-Host $_ -ForegroundColor Green
        } else {
            Write-Host $_ -ForegroundColor Gray
        }
    }
}
