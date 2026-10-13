package com.stickermaker.whatsapp.utils

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {
    private const val PREFS_NAME = "sticker_maker_preferences"
    private const val KEY_LANGUAGE = "selected_language"

    const val LANG_ES = "es"
    const val LANG_EN = "en"

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANGUAGE, null)
        if (saved != null) return saved

        val systemLang = Locale.getDefault().language
        return if (systemLang.startsWith("es", ignoreCase = true)) LANG_ES else LANG_EN
    }

    fun setLocale(context: Context, languageCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()

        val appLocale = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(appLocale)

        val locale = Locale(languageCode)
        Locale.setDefault(locale)
    }

    fun applyLanguageContext(context: Context): Context {
        val languageCode = getLanguage(context)
        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
