package com.stickermaker.whatsapp.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import com.stickermaker.whatsapp.BuildConfig
import com.stickermaker.whatsapp.model.StickerPack

object WhatsAppChecker {

    const val EXTRA_STICKER_PACK_ID = "extra_sticker_pack_id"
    const val EXTRA_STICKER_PACK_AUTHORITY = "extra_sticker_pack_authority"
    const val EXTRA_STICKER_PACK_NAME = "extra_sticker_pack_name"

    const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.sticker.intent.action.ENABLE_STICKER_PACK"
    const val WHATSAPP_PACKAGE_NAME = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE_NAME = "com.whatsapp.w4b"

    fun isWhatsAppInstalled(context: Context): Boolean {
        return isPackageInstalled(context, WHATSAPP_PACKAGE_NAME) || isPackageInstalled(context, WHATSAPP_BUSINESS_PACKAGE_NAME)
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Checks if sticker pack is already whitelisted in WhatsApp.
     */
    fun isPackWhitelisted(context: Context, identifier: String): Boolean {
        return try {
            val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
            val uri = Uri.parse("content://com.whatsapp.provider.sticker_whitelist_check/is_whitelisted")
                .buildUpon()
                .appendQueryParameter("authority", authority)
                .appendQueryParameter("identifier", identifier)
                .build()

            val cursor = context.contentResolver.query(uri, arrayOf("result"), null, null, null)
            var isWhitelisted = false
            cursor?.use {
                if (it.moveToFirst()) {
                    isWhitelisted = it.getInt(0) == 1
                }
            }
            isWhitelisted
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sends the sticker pack to WhatsApp.
     */
    fun addPackToWhatsApp(activity: Activity, pack: StickerPack, requestCode: Int) {
        if (!isWhatsAppInstalled(activity)) {
            Toast.makeText(activity, "WhatsApp no está instalado en este dispositivo", Toast.LENGTH_LONG).show()
            return
        }

        if (pack.stickers.size < 3) {
            Toast.makeText(
                activity,
                "WhatsApp requiere un mínimo de 3 stickers por paquete (actual: ${pack.stickers.size})",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (pack.stickers.size > 30) {
            Toast.makeText(
                activity,
                "WhatsApp permite un máximo de 30 stickers por paquete",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            val intent = Intent().apply {
                action = ACTION_ENABLE_STICKER_PACK
                putExtra(EXTRA_STICKER_PACK_ID, pack.identifier)
                putExtra(EXTRA_STICKER_PACK_AUTHORITY, BuildConfig.CONTENT_PROVIDER_AUTHORITY)
                putExtra(EXTRA_STICKER_PACK_NAME, pack.name)

                // Prefer regular WhatsApp if installed, else Business
                if (isPackageInstalled(activity, WHATSAPP_PACKAGE_NAME)) {
                    setPackage(WHATSAPP_PACKAGE_NAME)
                } else if (isPackageInstalled(activity, WHATSAPP_BUSINESS_PACKAGE_NAME)) {
                    setPackage(WHATSAPP_BUSINESS_PACKAGE_NAME)
                }
            }
            activity.startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            Toast.makeText(activity, "Error al abrir WhatsApp: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
