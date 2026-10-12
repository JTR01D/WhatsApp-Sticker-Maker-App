[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Host.UI.RawUI.WindowTitle = "Logs en tiempo real - WhatsApp Sticker Maker"

Clear-Host

$adb = "C:\Users\juane\Downloads\platform-tools\adb.exe"

if (-not (Test-Path $adb)) {
    Write-Host "Error: No se encontro adb.exe en $adb" -ForegroundColor Red
    pause
    exit
}

# Obtener informacion del dispositivo conectado
$deviceLine = & $adb devices | Where-Object { $_ -match "\bdevice$" } | Select-Object -First 1
$deviceModel = "No detectado"
$deviceSerial = ""
if ($deviceLine) {
    $deviceSerial = ($deviceLine -split "\s+")[0]
    $rawModel = (& $adb -s $deviceSerial shell getprop ro.product.model 2>$null)
    if ($rawModel) { $deviceModel = $rawModel.Trim() }
}

# Banner informativo
Write-Host "+==============================================================================+" -ForegroundColor Cyan
Write-Host "|           LOGS EN TIEMPO REAL - WHATSAPP & STICKER MAKER                     |" -ForegroundColor Cyan
Write-Host "+==============================================================================+" -ForegroundColor Cyan
$devInfo = "$deviceModel ($deviceSerial)"
$padLen = [Math]::Max(0, 58 - $devInfo.Length)
Write-Host "|  Dispositivo : " -ForegroundColor Cyan -NoNewline
Write-Host $devInfo -ForegroundColor Yellow -NoNewline
Write-Host (" " * $padLen) -NoNewline
Write-Host "|" -ForegroundColor Cyan

Write-Host "|  Convencion de Colores:                                                      |" -ForegroundColor Cyan
Write-Host "|     " -ForegroundColor Cyan -NoNewline
Write-Host " ERROR " -ForegroundColor White -BackgroundColor DarkRed -NoNewline
Write-Host " Fallos / Excepciones    " -ForegroundColor Red -NoNewline
Write-Host " WARN  " -ForegroundColor Black -BackgroundColor DarkYellow -NoNewline
Write-Host " Advertencias / Glide     |" -ForegroundColor Yellow
Write-Host "|     " -ForegroundColor Cyan -NoNewline
Write-Host " INFO  " -ForegroundColor Black -BackgroundColor DarkGreen -NoNewline
Write-Host " Eventos / Exito         " -ForegroundColor Green -NoNewline
Write-Host " DEBUG " -ForegroundColor Black -BackgroundColor DarkCyan -NoNewline
Write-Host " Diagnostico detallado    |" -ForegroundColor Cyan
Write-Host "|                                                                              |" -ForegroundColor Cyan
Write-Host "|  Etiquetas   : " -ForegroundColor Cyan -NoNewline
Write-Host "[Provider]" -ForegroundColor Magenta -NoNewline
Write-Host " " -NoNewline
Write-Host "[WhatsApp]" -ForegroundColor Green -NoNewline
Write-Host " " -NoNewline
Write-Host "[Validator]" -ForegroundColor Cyan -NoNewline
Write-Host " " -NoNewline
Write-Host "[Glide]" -ForegroundColor Yellow -NoNewline
Write-Host " " -NoNewline
Write-Host "[App]" -ForegroundColor DarkCyan -NoNewline
Write-Host "                       |" -ForegroundColor Cyan
Write-Host "|  Detener     : Presiona " -ForegroundColor Cyan -NoNewline
Write-Host "Ctrl + C" -ForegroundColor White -BackgroundColor DarkMagenta -NoNewline
Write-Host " para finalizar la sesion.                        |" -ForegroundColor Cyan
Write-Host "+==============================================================================+" -ForegroundColor Cyan
Write-Host ""

# Limpiar buffer anterior de logcat
Write-Host "-> Limpiando buffer de logcat previo..." -ForegroundColor DarkGray
& $adb logcat -c

Write-Host "-> Escuchando eventos del telefono en vivo..." -ForegroundColor Green
Write-Host ""

# Filtros relevantes
$pattern = "stickermaker|whatsapp|StickerContentProvider|AddThirdPartyStickerPackActivity|StickerPackValidator|WhatsAppChecker|StickerStorage|Glide"

# Control de stacktrace para marcar continuaciones en rojo
$lastWasError = $false

& $adb logcat -v time | ForEach-Object {
    $line = $_

    # Linea de formato estandar logcat: "MM-dd HH:mm:ss.fff L/Tag( PID): Mensaje"
    if ($line -match '^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEF])\/(.*?)\(\s*(\d+)\s*\):\s*(.*)$') {
        $timeStr = $Matches[1]
        $timeShort = ($timeStr -split '\s+')[1]
        $level = $Matches[2]
        $tag = $Matches[3].Trim()
        $procId = $Matches[4]
        $msg = $Matches[5]

        # Excluir ruido del sistema (Xiaomi MIUI, daemons de red, baterias, contactos, apps de terceros)
        $isNoise = ($tag -match "(collectorManager|AppsFilter|ContactsProvider|SyncAccountUtils|ConnectivityService|Wifi|Factory|Power|Network|PackageManager|BufferQueue|libPower|nativeloader|WindowManager|ActivityTaskManager|ActivityManager|ForceDark|thermal|facebook|fb4a|katana|ProcessManager|Zygote|AppOps|CoreBackPreview|NotificationService|game_scn|DaemonThread|audit|libPowerHal|thermal_src|SurfaceFlinger|InputDispatcher|InputReader|OomAdjuster|MIUIInput|BufferStateLayer|GoogleInputMethodService|UxUtility|surfaceflinger|ContentCapturePerUserService|AbstractIme)") -or
                   ($msg -match "(ThermalScenarioValue|QuickPerformanceLogger|queryReadonlyAccountTypes|Contacts query|PreloadedFbnsService|WebInstallAppLink|interaction: PackageSetting|Relayout Window|perfNotifyAppState|mDesktopPkgSet|Toast already killed|Changing focus|wms\.Input focus|wms\.Focus not requested|finishDrawingLocked|showSurfaceRobustly|Update InputWindowHandle|setParent old|notifyForegroundApp|handleFpsSwitch|LatinIme|EditorInfo|MotionEvent|SF client|setTransactionState)")

        if ($isNoise) {
            return
        }

        # Comprobar si coincide con eventos reales de nuestra app o de stickers
        $isMyAppTag = ($tag -match "^(MainActivity|PackDetailActivity|StickerStorage|ImageProcessor|StickerContentProvider|StickerPackValidator|WhatsAppChecker|StickerPackAdapter|StickerGridAdapter)$")
        $isStickerTag = ($tag -match "(StickerContentProvider|StickerPackValidator|WhatsAppChecker|AddThirdPartyStickerPackActivity)")
        $isWhatsAppSticker = ($tag -match "WhatsApp" -and $msg -match "sticker|third_party|whitelist|provider|content://|authority|AddThirdParty")
        $isAppLog = ($tag -match "stickermaker" -or ($tag -match "Glide" -and $msg -match "com.stickermaker.whatsapp"))

        $isRelevant = $isMyAppTag -or $isStickerTag -or $isWhatsAppSticker -or $isAppLog -or ($level -in @("E", "F") -and $msg -match "com\.stickermaker\.whatsapp")

        if (-not $isRelevant) {
            $lastWasError = $false
            return
        }

        # Timestamp
        Write-Host "[$timeShort] " -ForegroundColor DarkGray -NoNewline

        # Badge de Nivel
        switch ($level) {
            "E" {
                Write-Host " ERROR " -ForegroundColor White -BackgroundColor DarkRed -NoNewline
                $lastWasError = $true
            }
            "F" {
                Write-Host " FATAL " -ForegroundColor White -BackgroundColor DarkRed -NoNewline
                $lastWasError = $true
            }
            "W" {
                Write-Host " WARN  " -ForegroundColor Black -BackgroundColor DarkYellow -NoNewline
                $lastWasError = $false
            }
            "I" {
                Write-Host " INFO  " -ForegroundColor Black -BackgroundColor DarkGreen -NoNewline
                $lastWasError = $false
            }
            "D" {
                Write-Host " DEBUG " -ForegroundColor Black -BackgroundColor DarkCyan -NoNewline
                $lastWasError = $false
            }
            default {
                Write-Host " VERB  " -ForegroundColor DarkGray -NoNewline
                $lastWasError = $false
            }
        }

        Write-Host " " -NoNewline

        # Tag con color tematico
        if ($tag -match "StickerContentProvider") {
            Write-Host "[$tag]" -ForegroundColor Magenta -NoNewline
        } elseif ($tag -match "WhatsApp|AddThirdPartyStickerPackActivity|WhatsAppChecker") {
            Write-Host "[$tag]" -ForegroundColor Green -NoNewline
        } elseif ($tag -match "StickerPackValidator") {
            Write-Host "[$tag]" -ForegroundColor Cyan -NoNewline
        } elseif ($tag -match "Glide") {
            Write-Host "[$tag]" -ForegroundColor Yellow -NoNewline
        } elseif ($tag -match "MainActivity|PackDetailActivity|StickerStorage|StickerPackAdapter") {
            Write-Host "[$tag]" -ForegroundColor DarkCyan -NoNewline
        } else {
            Write-Host "[$tag]" -ForegroundColor Gray -NoNewline
        }

        Write-Host ": " -ForegroundColor DarkGray -NoNewline

        # Mensaje con color semantico
        if ($level -in @("E", "F") -or $msg -match "Exception|Error|fail|FATAL|Fallo") {
            Write-Host $msg -ForegroundColor Red
        } elseif ($level -eq "W" -or $msg -match "warn|Failed to find") {
            Write-Host $msg -ForegroundColor Yellow
        } elseif ($msg -match "exito|success|agregado|added|OK|isPackWhitelisted.*true") {
            Write-Host $msg -ForegroundColor Green
        } elseif ($msg -match "query|cursor|uri=|mode=|resolvio|openFile|openAssetFile") {
            Write-Host $msg -ForegroundColor Cyan
        } elseif ($level -eq "I") {
            Write-Host $msg -ForegroundColor White
        } else {
            Write-Host $msg -ForegroundColor Gray
        }
    } else {
        # Lineas de continuacion (Stack traces, Caused by, etc.)
        if ($line -match "^\s+at\s+|^\s*Caused by:|Exception|Error") {
            Write-Host "             $line" -ForegroundColor Red
        } elseif ($lastWasError) {
            Write-Host "             $line" -ForegroundColor DarkRed
        } elseif ($line -match $pattern) {
            Write-Host "             $line" -ForegroundColor DarkGray
        }
    }
}
