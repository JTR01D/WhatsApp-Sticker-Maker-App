# 🎨 WhatsApp Sticker Maker (Android)

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_7.0+-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Language" />
  <img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge" alt="License" />
  <img src="https://img.shields.io/badge/Privacy-100%25_Offline-success?style=for-the-badge" alt="100% Offline" />
  <img src="https://img.shields.io/badge/Stickers-Static_%26_Animated-00A884?style=for-the-badge&logo=whatsapp&logoColor=white" alt="WhatsApp Stickers" />
</p>

---

### 🌐 Language / Idioma
- [🇪🇸 Versión en Español](#-versión-en-español)
- [🇺🇸 English Version](#-english-version)

---

<a name="-versión-en-español"></a>
## 🇪🇸 Versión en Español

**WhatsApp Sticker Maker** es una aplicación nativa para Android desarrollada en Kotlin, creada para diseñar, importar, convertir y administrar colecciones completas de **stickers estáticos y animados** para **WhatsApp** y **WhatsApp Business**, con una interfaz moderna con efectos **Liquid Glass & Acrylic**, funcionamiento **100% offline y privado**, y soporte de exportación/importación en formato **.ZIP**.

### ✨ Características Principales

* 🎬 **Stickers Estáticos y Animados**:
  * Compatibilidad total con WebP animados y estáticos.
  * Normalización automática a lienzo cuadrado de **512 x 512 píxeles** preservando la proporción original sin distorsiones.
  * Control estricto de límites oficiales de WhatsApp: ≤ 100 KB para estáticos y ≤ 500 KB para animados.
  * Generación automática de ícono de bandeja (*tray icon*) de **96 x 96 píxeles** (< 50 KB).
* 🔍 **Detección Inteligente de Duplicados**:
  * Doble verificación mediante **MD5 Payload Hash** (huella binaria exacta) y **dHash** (similitud visual perceptual) para prevenir stickers repetidos.
* 📦 **Portabilidad y Respaldo Completo (.ZIP)**:
  * Exporta paquetes individuales o tu colección completa con un solo toque a archivos `.zip`.
  * Importa archivos ZIP directamente desde la app o abriéndolos desde cualquier explorador de archivos.
* 💎 **Diseño Moderno Liquid Glass**:
  * Barras y diálogos translúcidos con efecto de cristal líquido y desenfoque por hardware (`FLAG_BLUR_BEHIND`).
  * Botones con estilo acrílico y diseño optimizado para modo oscuro (*Dark Mode*).
* 🌐 **Soporte Bilingüe (Español / Inglés)**:
  * Selector dinámico de idioma en caliente desde el menú de opciones (Español 🇪🇸 / English 🇺🇸).
* 🛡️ **Seguridad y Privacidad por Diseño**:
  * **100% Local / Cero Telemetría**: La app **NO solicita el permiso `INTERNET`**, garantizando que ninguna imagen o dato salga del dispositivo.
  * Protección activa contra vulnerabilidades *Zip Slip* y *Path Traversal* en el `ContentProvider`.
  * Integración oficial con WhatsApp mediante IPC y `ContentProvider` verificado.

---

### 🚀 Descarga y Compilación (GitHub Actions)

No necesitas tener Android Studio ni el SDK de Android instalados en tu computadora. El proyecto incluye un flujo de compilación automática en la nube con **GitHub Actions**:

1. Ve a la pestaña **[Actions](../../actions)** de este repositorio en GitHub.
2. Selecciona la ejecución más reciente del flujo **"Build WhatsApp Sticker Maker APK"**.
3. En la sección inferior **"Artifacts"**, descarga el archivo ZIP generado.
4. Descomprime el archivo e instala el `.apk` en tu dispositivo Android.

---

### 📖 Cómo Usar la Aplicación

1. **Crear Paquete**: Toca el botón flotante **"+ Crear Paquete"**, escribe un nombre y autor.
2. **Añadir Imágenes**: Abre el paquete y pulsa **"Añadir Stickers"**. Puedes seleccionar archivos individuales, carpetas completas o archivos `.zip`.
3. **Optimización Automática**: La app redimensionará, convertirá a WebP 512x512 y comprobará duplicados automáticamente.
4. **Añadir a WhatsApp**: Cuando tengas al menos 3 stickers (máximo 30 por paquete), presiona el botón **"Añadir a WhatsApp"** y confirma en la ventana de WhatsApp.

---

<a name="-english-version"></a>
## 🇺🇸 English Version

**WhatsApp Sticker Maker** is a native Android application built in Kotlin, designed to craft, import, optimize, and manage full collections of **static and animated stickers** for **WhatsApp** and **WhatsApp Business**. Featuring a sleek **Liquid Glass & Acrylic** user interface, **100% offline privacy**, and portable **.ZIP** backup capabilities.

### ✨ Key Features

* 🎬 **Animated & Static Stickers Support**:
  * Full support for both animated and static WebP stickers.
  * Automatic scaling and centering to exact **512 x 512 pixels** square canvas without aspect ratio distortion.
  * Strict compliance with official WhatsApp limits: ≤ 100 KB for static stickers and ≤ 500 KB for animated stickers.
  * Automatic generation of the required **96 x 96 pixels** tray icon (< 50 KB).
* 🔍 **Smart Deduplication Engine**:
  * Dual-layer duplicate detection using **MD5 binary payload hashing** and **dHash perceptual visual hashing** to prevent redundant stickers.
* 📦 **Portable .ZIP Backup & Sharing**:
  * Export single packs or your whole sticker library into portable `.zip` archives.
  * Import packs or collections directly from `.zip` files via app file picker or system intents.
* 💎 **Liquid Glass & Acrylic Design**:
  * Translucent glass headers, toolbars, and modal dialogs with hardware-accelerated blur (`FLAG_BLUR_BEHIND`).
  * Smooth micro-interactions, dark mode tailored color palette, and acrylic action buttons.
* 🌐 **Bilingual Support (English / Spanish)**:
  * In-app dynamic language switcher with real-time UI refresh (English 🇺🇸 / Spanish 🇪🇸).
* 🛡️ **Privacy & Security by Design**:
  * **100% Offline / Zero Telemetry**: The app **does NOT request the `INTERNET` permission**, ensuring photos and metadata never leave your phone.
  * Secure storage with canonical path validations against *Zip Slip* and *Path Traversal* attacks in the ContentProvider.
  * Official WhatsApp IPC protocol integration via secured `ContentProvider`.

---

### 🚀 Download & Build (GitHub Actions CI)

You do not need Android Studio or the Android SDK installed on your PC. This repository includes automated cloud builds via **GitHub Actions**:

1. Go to the **[Actions](../../actions)** tab of this repository.
2. Click on the latest workflow run for **"Build WhatsApp Sticker Maker APK"**.
3. Under the **"Artifacts"** section, download the build artifact.
4. Unzip the downloaded file and install the `.apk` on your Android device.

---

### 📖 How to Use

1. **Create Pack**: Tap the **"+ Create Pack"** button and enter a pack name and creator name.
2. **Add Images**: Open the pack and tap **"Add Stickers"**. You can select individual images, entire folders, or `.zip` archives.
3. **Auto Processing**: The engine automatically converts, resizes to 512x512 WebP, and deduplicates your stickers.
4. **Add to WhatsApp**: Once your pack contains between 3 and 30 stickers, tap **"Add to WhatsApp"** and confirm in WhatsApp's prompt.

---

## 🛠️ Technical Specifications / Especificaciones Técnicas

| Attribute / Atributo | Specification / Especificación |
| :--- | :--- |
| **Language / Lenguaje** | Kotlin 1.9+ (JVM Target 17) |
| **Min SDK** | Android 7.0 (API 24) |
| **Target / Compile SDK** | Android 14 (API 34) |
| **UI Framework** | Android Views + ViewBinding + Material Design 3 |
| **Image Engine** | Custom WebP Processor + Glide 4.16.0 |
| **JSON Serialization** | Google Gson 2.10.1 |
| **Permissions / Permisos** | `READ_MEDIA_IMAGES` (API 33+), `READ_EXTERNAL_STORAGE` (≤ API 32) |
| **Network / Red** | **None (No INTERNET permission declared)** |

---

## 📄 License / Licencia

This project is licensed under the **MIT License**.
Este proyecto está bajo la licencia **MIT**.
