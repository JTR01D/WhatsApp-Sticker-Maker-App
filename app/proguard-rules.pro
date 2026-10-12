# Proguard rules for WhatsApp Sticker Maker
-keep class com.stickermaker.whatsapp.model.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
