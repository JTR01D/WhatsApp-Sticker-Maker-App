# 📱 WhatsApp Sticker Maker - Creador de Stickers para Android

Aplicación nativa para Android (Kotlin) diseñada para importar y convertir imágenes desde **cualquier ubicación del almacenamiento del teléfono móvil** (Galería, Descargas, Google Fotos, tarjeta SD, etc.) y agregarlas directamente como paquetes de stickers a **WhatsApp**.

---

## ✨ Características Principales

- **Selección libre de archivos:** Usa el explorador del sistema Android (Storage Access Framework) para elegir una o múltiples imágenes a la vez desde cualquier carpeta del dispositivo.
- **Conversión y optimización automática:**
  - Ajusta y centra las imágenes a **512 x 512 píxeles** (respetando la relación de aspecto sin deformarlas).
  - Convierte las imágenes al formato oficial **WebP**.
  - Comprime las imágenes automáticamente para garantizar que pesen **menos de 100 KB** (requisito estricto del protocolo de WhatsApp).
  - Genera automáticamente el icono de bandeja (tray icon) de **96 x 96 píxeles** (< 50 KB).
- **Compatibilidad 100% con WhatsApp:** Implementa el `ContentProvider` oficial y el intent `com.whatsapp.sticker.intent.action.ENABLE_STICKER_PACK`.
- **Validaciones automáticas:** Controla el rango reglamentario de WhatsApp (mínimo 3 stickers, máximo 30 por paquete) y avisa en pantalla cuando el paquete está listo.
- **Soporte para WhatsApp estándar y WhatsApp Business.**

---

## 🚀 Cómo obtener el archivo APK (Compilación en la nube con GitHub Actions)

No necesitas tener Android Studio ni el SDK de Android instalados en tu computadora. El repositorio incluye un flujo automatizado de **GitHub Actions** (`.github/workflows/build-apk.yml`) que compila la APK en los servidores de GitHub gratis.

### Pasos:
1. **Sube el proyecto a GitHub:**
   Crea un repositorio en [GitHub](https://github.com/new) y sube los archivos de esta carpeta:
   ```bash
   git init
   git add .
   git commit -m "Initial commit - WhatsApp Sticker Maker"
   git branch -M main
   git remote add origin https://github.com/TU-USUARIO/TU-REPOSITORIO.git
   git push -u origin main
   ```
2. **Ejecución automática:**
   Al hacer push, GitHub Actions iniciará automáticamente el flujo **"Build Android APK"**.
3. **Descarga tu APK:**
   - En tu repositorio de GitHub, haz clic en la pestaña **"Actions"**.
   - Haz clic en la última ejecución de compilación completada.
   - En la sección inferior llamada **"Artifacts"**, descarga el archivo **`WhatsAppStickerMaker-APK`**.
   - Descomprime el archivo ZIP y pasa el archivo `.apk` a tu teléfono móvil.
   - En tu teléfono, activa "Instalar aplicaciones de fuentes desconocidas" y abre el APK para instalarlo.

---

## 🛠️ Compilación Local con Android Studio (Opcional)

Si tienes Android Studio instalado:
1. Abre Android Studio y selecciona **Open**.
2. Selecciona la carpeta de este proyecto.
3. Espera a que Gradle sincronice las dependencias.
4. Conecta tu teléfono mediante cable USB (con depuración USB activada) o un emulador.
5. Haz clic en el botón verde **Run** (`Shift + F10`) o ve al menú **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

---

## 📖 Cómo usar la aplicación en tu móvil

1. Abre la aplicación **Sticker Maker WhatsApp**.
2. Toca en el botón **"Crear Paquete"** e ingresa un nombre (ejemplo: *Mis Stickers*) y tu nombre como autor.
3. Entra al paquete recién creado y presiona **"Añadir Stickers"**.
4. Se abrirá el explorador de archivos de tu móvil: selecciona tus imágenes favoritas (JPG, PNG, WebP) desde Descargas, Fotos o WhatsApp.
5. La aplicación procesará y optimizará cada imagen a 512x512 WebP.
6. Cuando tengas al menos **3 stickers** en el paquete, el botón verde **"Añadir a WhatsApp"** se habilitará.
7. Presiona el botón y confirma en la ventana emergente de WhatsApp para tener tus stickers listos en tus chats.
