package org.cortex.terminal

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import java.util.Locale

/**
 * App language handling. By default the UI follows the system language; Settings can pin one of
 * the languages listed in `R.array.language_values`.
 *
 *  - Android 13+ stores per-app languages itself (and lists them in the system settings through
 *    `res/xml/locales_config.xml`); [AppCompatDelegate.setApplicationLocales] forwards to it.
 *  - Older versions get the saved choice applied on every process start by [applySaved].
 */
object LocaleHelper {
    const val PREF_KEY = "app_language"
    const val SYSTEM = LanguageTags.SYSTEM

    /** Applies the saved language on Android < 13. Call from `Application.onCreate`. */
    fun applySaved(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val tag = try {
            PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, SYSTEM)
        } catch (_: Exception) {
            SYSTEM
        }
        AppCompatDelegate.setApplicationLocales(localesFor(tag))
    }

    /** Switches the app language now; [SYSTEM] goes back to following the device. */
    fun setLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(localesFor(tag))
    }

    /** The entry of `language_values` that matches what is currently active. */
    fun currentSelection(available: List<String>): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return SYSTEM
        return LanguageTags.match(locales[0]?.toLanguageTag(), available)
    }

    /**
     * Context whose resources use the chosen language, for places that are not an AppCompat
     * activity (the foreground service, notifications). Android 13+ applies the per-app language
     * to every context already.
     */
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val tag = try {
            PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, SYSTEM)
        } catch (_: Exception) {
            SYSTEM
        }
        if (tag.isNullOrEmpty() || tag == SYSTEM) return context
        return try {
            val config = Configuration(context.resources.configuration)
            config.setLocale(Locale.forLanguageTag(tag))
            context.createConfigurationContext(config)
        } catch (_: Exception) {
            context
        }
    }

    internal fun localesFor(tag: String?): LocaleListCompat =
        if (tag.isNullOrEmpty() || tag == SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
}
