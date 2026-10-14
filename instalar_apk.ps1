# ==============================================================================
# Script de Instalacion Automatizada - WhatsApp Sticker Maker
# Copia a Descargas en el telefono e instala silenciosamente conservando datos
# ==============================================================================

param(
    [string]$ApkPath = "",
    [switch]$DownloadLatest = $false,
    [switch]$AutoLaunch = $false
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Host.UI.RawUI.WindowTitle = "Instalador APK - WhatsApp Sticker Maker"

Clear-Host
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   WhatsApp Sticker Maker - Instalador Directo ADB               " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host ""

# 1. Localizar adb.exe
$adbCandidates = @(
    "adb",
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:USERPROFILE\Downloads\platform-tools\adb.exe"
)
$adb = $null
foreach ($candidate in $adbCandidates) {
    if (Get-Command $candidate -ErrorAction SilentlyContinue) {
        $adb = $candidate
        break
    } elseif (Test-Path $candidate) {
        $adb = $candidate
        break
    }
}

if (-not $adb) {
    Write-Host "[!] Error: No se encontro adb.exe." -ForegroundColor Red
    Write-Host "Asegurate de tener Android SDK o platform-tools en Downloads o en el PATH." -ForegroundColor Yellow
    exit 1
}

Write-Host "[OK] ADB localizado: " -ForegroundColor Gray -NoNewline
Write-Host $adb -ForegroundColor White

# 2. Verificar dispositivo conectado
$devicesOutput = & $adb devices
$connectedDevices = @()
$devicesLines = $devicesOutput -split "`r?`n"
for ($i = 1; $i -lt $devicesLines.Count; $i++) {
    $line = $devicesLines[$i].Trim()
    if ($line -and $line -match "\s+device$") {
        $deviceId = ($line -split "\s+")[0]
        $connectedDevices += $deviceId
    }
}

if ($connectedDevices.Count -eq 0) {
    Write-Host "[!] No se detecto ningun dispositivo Android conectado con Depuracion USB activa." -ForegroundColor Red
    Write-Host "Conecta tu telefono por USB, desbloquealo y activa 'Depuracion USB'." -ForegroundColor Yellow
    exit 1
}

$targetDevice = $connectedDevices[0]
Write-Host "[OK] Dispositivo detectado: " -ForegroundColor Gray -NoNewline
Write-Host $targetDevice -ForegroundColor Green

# 3. Localizar o Descargar el archivo APK
$sourceFile = $null
$officialDownloadUrl = "https://github.com/JTR01D/WhatsApp-Sticker-Maker-App/releases/download/latest/WhatsAppStickerMaker.apk"
$destPath = "$env:USERPROFILE\Downloads\WhatsAppStickerMaker.apk"

if ($ApkPath -and (Test-Path $ApkPath)) {
    $sourceFile = Get-Item $ApkPath
} elseif ($DownloadLatest) {
    Write-Host ""
    Write-Host "[*] Descargando la compilacion mas reciente desde GitHub Releases..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri $officialDownloadUrl -OutFile $destPath
    $sourceFile = Get-Item $destPath
} else {
    Write-Host ""
    Write-Host "[*] Buscando versiones disponibles en Descargas..." -ForegroundColor Cyan
    $downloadsDir = "$env:USERPROFILE\Downloads"
    $recentFiles = Get-ChildItem -Path $downloadsDir | Where-Object {
        ($_.Name -like "*WhatsAppStickerMaker*" -or $_.Name -like "app-debug*.apk") -and
        ($_.Extension -ieq ".apk" -or $_.Extension -ieq ".zip" -or ($_.PSIsContainer -and (Get-ChildItem $_.FullName -Filter "*.apk" -Recurse).Count -gt 0))
    } | Sort-Object LastWriteTime -Descending

    if ($recentFiles -and $recentFiles.Count -gt 0) {
        $localFile = $recentFiles[0]
        Write-Host ""
        Write-Host "Selecciona una opcion de origen:" -ForegroundColor Yellow
        Write-Host " [1] Descargar la compilacion mas reciente desde GitHub Releases (Recomendado)" -ForegroundColor Green
        Write-Host " [2] Usar archivo local encontrado: $($localFile.Name) (Modificado: $($localFile.LastWriteTime))" -ForegroundColor Cyan
        $choice = Read-Host "Ingresa tu opcion (1 o 2, Enter para 1)"
        if ($choice -eq "2") {
            $sourceFile = $localFile
        } else {
            Write-Host "[*] Descargando desde GitHub: $officialDownloadUrl ..." -ForegroundColor Cyan
            Invoke-WebRequest -Uri $officialDownloadUrl -OutFile $destPath
            $sourceFile = Get-Item $destPath
        }
    } else {
        Write-Host "[*] No se encontraron archivos locales. Descargando la ultima version desde GitHub Releases..." -ForegroundColor Cyan
        Invoke-WebRequest -Uri $officialDownloadUrl -OutFile $destPath
        $sourceFile = Get-Item $destPath
    }
}

Write-Host "[OK] Archivo/Carpeta seleccionada: " -ForegroundColor Gray -NoNewline
Write-Host $sourceFile.Name -ForegroundColor White -NoNewline
Write-Host " (Modificado: $($sourceFile.LastWriteTime))" -ForegroundColor Gray

# 4. Obtener la ruta final del APK
$finalApkPath = $null

if ($sourceFile.PSIsContainer) {
    $apksFound = Get-ChildItem -Path $sourceFile.FullName -Filter "*.apk" -Recurse
    if ($apksFound.Count -gt 0) {
        $finalApkPath = $apksFound[0].FullName
    } else {
        Write-Host "[!] Error: No se encontro ningun archivo .apk dentro de la carpeta." -ForegroundColor Red
        exit 1
    }
} elseif ($sourceFile.Extension -ieq ".zip") {
    $tempExtractDir = Join-Path $env:TEMP ("WSM_Extract_" + [System.Guid]::NewGuid().ToString().Substring(0, 8))
    Write-Host "[*] Descomprimiendo archivo ZIP..." -ForegroundColor Cyan
    Expand-Archive -Path $sourceFile.FullName -DestinationPath $tempExtractDir -Force
    
    $apksFound = Get-ChildItem -Path $tempExtractDir -Filter "*.apk" -Recurse
    if ($apksFound.Count -eq 0) {
        Write-Host "[!] Error: No se encontro ningun archivo .apk dentro del ZIP." -ForegroundColor Red
        exit 1
    }
    $finalApkPath = $apksFound[0].FullName
} elseif ($sourceFile.Extension -ieq ".apk") {
    $finalApkPath = $sourceFile.FullName
} else {
    Write-Host "[!] El archivo seleccionado no es un .apk ni un .zip valido." -ForegroundColor Red
    exit 1
}

$apkSizeMB = [Math]::Round(((Get-Item $finalApkPath).Length / 1MB), 2)
Write-Host "[OK] APK listo para transferir: " -ForegroundColor Gray -NoNewline
Write-Host "$finalApkPath ($apkSizeMB MB)" -ForegroundColor White

# 5. Copiar a la carpeta Descargas del telefono (/sdcard/Download)
Write-Host ""
Write-Host "[1/3] Copiando APK a la carpeta Descargas del dispositivo..." -ForegroundColor Cyan
$remoteDownloadPath = "/storage/emulated/0/Download/WhatsAppStickerMaker.apk"
& $adb -s $targetDevice push $finalApkPath $remoteDownloadPath
if ($LASTEXITCODE -ne 0) {
    Write-Host "[!] Advertencia: No se pudo copiar a /sdcard/Download, continuando con la instalacion..." -ForegroundColor Yellow
} else {
    Write-Host "[OK] Guardado en telefono: " -ForegroundColor Green -NoNewline
    Write-Host $remoteDownloadPath -ForegroundColor White
}

# 6. Transferir a /data/local/tmp e instalar via pm install -r -d
Write-Host "[2/3] Transfiriendo para instalacion sin restricciones..." -ForegroundColor Cyan
$remoteTmpPath = "/data/local/tmp/WhatsAppStickerMaker.apk"
& $adb -s $targetDevice push $finalApkPath $remoteTmpPath | Out-Null

Write-Host "[3/3] Ejecutando instalacion segura conservando datos (pm install -r -d)..." -ForegroundColor Cyan
$installOutput = & $adb -s $targetDevice shell pm install -r -d $remoteTmpPath
Write-Host $installOutput -ForegroundColor Gray

if ($installOutput -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
    Write-Host ""
    Write-Host "[!] Cambio de firma detectado entre compilaciones de CI." -ForegroundColor Yellow
    Write-Host "[*] Protegiendo tus stickers: Guardando copia de seguridad automatica..." -ForegroundColor Cyan
    
    $backupDir = "$env:TEMP\WSM_DataBackup"
    if (-not (Test-Path $backupDir)) { New-Item -ItemType Directory -Path $backupDir -Force | Out-Null }
    $backupFile = Join-Path $backupDir ("wsm_backup_" + (Get-Date -Format "yyyyMMdd_HHmmss") + ".tar")
    
    cmd.exe /c """$adb"" -s $targetDevice exec-out run-as com.stickermaker.whatsapp.debug tar -cf - -C files . > ""$backupFile"""
    
    if ((Test-Path $backupFile) -and (Get-Item $backupFile).Length -gt 100) {
        Write-Host "[OK] Copia de seguridad guardada con exito en tu PC: $backupFile" -ForegroundColor Green
        
        Write-Host "[*] Reinstalando aplicacion con la nueva firma..." -ForegroundColor Cyan
        & $adb -s $targetDevice uninstall com.stickermaker.whatsapp.debug | Out-Null
        $installOutput = & $adb -s $targetDevice shell pm install $remoteTmpPath
        Write-Host $installOutput -ForegroundColor Gray
        
        if ($installOutput -match "Success") {
            Write-Host "[*] Restaurando paquetes y stickers..." -ForegroundColor Cyan
            & $adb -s $targetDevice shell am start -n com.stickermaker.whatsapp.debug/com.stickermaker.whatsapp.MainActivity | Out-Null
            Start-Sleep -Seconds 1
            & $adb -s $targetDevice shell am force-stop com.stickermaker.whatsapp.debug
            
            cmd.exe /c """$adb"" -s $targetDevice exec-in run-as com.stickermaker.whatsapp.debug tar -xf - -C files < ""$backupFile"""
            Write-Host "[OK] ¡Todos tus datos y stickers fueron restaurados con exito!" -ForegroundColor Green
        }
    } else {
        Write-Host "[!] No se pudo realizar la copia de seguridad. Reinstalacion cancelada para evitar perdida de datos." -ForegroundColor Red
    }
}

# Limpiar temporal en /data/local/tmp
& $adb -s $targetDevice shell rm $remoteTmpPath | Out-Null

if ($installOutput -match "Success") {
    Write-Host ""
    Write-Host "=================================================================" -ForegroundColor Green
    Write-Host "                INSTALACION EXITOSA                              " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Green
    
    # Obtener version instalada
    $versionInfo = & $adb -s $targetDevice shell dumpsys package com.stickermaker.whatsapp.debug | Select-String "versionCode|versionName"
    if ($versionInfo) {
        Write-Host "Version activa en el telefono:" -ForegroundColor Cyan
        foreach ($v in $versionInfo) {
            Write-Host "   $($v.Line.Trim())" -ForegroundColor White
        }
    }

    # Abrir aplicacion
    if ($AutoLaunch) {
        & $adb -s $targetDevice shell am start -n com.stickermaker.whatsapp.debug/com.stickermaker.whatsapp.MainActivity | Out-Null
        Write-Host "[OK] Aplicacion iniciada en pantalla." -ForegroundColor Green
    } else {
        Write-Host ""
        $abrirApp = Read-Host "Deseas abrir la aplicacion ahora en tu telefono? (s/n)"
        if ($abrirApp -match "^[sSyY]") {
            & $adb -s $targetDevice shell am start -n com.stickermaker.whatsapp.debug/com.stickermaker.whatsapp.MainActivity | Out-Null
            Write-Host "[OK] Aplicacion iniciada en pantalla." -ForegroundColor Green
        }
    }
} else {
    Write-Host ""
    Write-Host "[!] Error al instalar el APK:" -ForegroundColor Red
    Write-Host $installOutput -ForegroundColor Yellow
}

Write-Host ""
Write-Host "=================================================================" -ForegroundColor Cyan
