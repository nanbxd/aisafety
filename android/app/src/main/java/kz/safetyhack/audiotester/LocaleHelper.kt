package kz.safetyhack.audiotester

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Language management: system default + explicit ru / kk / en.
 * Preferred language is stored in SharedPreferences and applied before super.onCreate.
 */
object LocaleHelper {
    private const val PREFS = "skepticall_locale"
    private const val KEY = "language"          // "" | "system" | "ru" | "kk" | "en"

    const val SYSTEM = "system"
    const val RU = "ru"
    const val KK = "kk"
    const val EN = "en"

    fun preferredCode(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, SYSTEM) ?: SYSTEM
        return if (raw.isBlank() || raw == SYSTEM) SYSTEM else raw
    }

    fun setPreferred(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, code)
            .apply()
    }

    /** Call from Activity.attachBaseContext / onCreate before setContent. */
    fun wrap(context: Context): Context {
        val code = preferredCode(context)
        val locale = when (code) {
            RU -> Locale("ru")
            KK -> Locale("kk")
            EN -> Locale.ENGLISH
            else -> systemLocale()
        }
        return updateResources(context, locale)
    }

    fun systemLocale(): Locale {
        return if (Build.VERSION.SDK_INT >= 24) {
            LocaleList.getDefault()[0]
        } else {
            @Suppress("DEPRECATION")
            Locale.getDefault()
        }
    }

    private fun updateResources(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        val theme = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getInt("theme", 0)
        if (theme != 0) config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (theme == 2) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        if (Build.VERSION.SDK_INT >= 24) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return context.createConfigurationContext(config)
    }

    /** Restart activity so new resources are loaded. */
    fun applyAndRecreate(activity: Activity, code: String) {
        setPreferred(activity, code)
        val intent = activity.intent
        activity.finish()
        activity.startActivity(intent)
        activity.overridePendingTransition(0, 0)
    }

    fun displayName(code: String, context: Context): String = when (code) {
        RU -> context.getString(R.string.lang_russian)
        KK -> context.getString(R.string.lang_kazakh)
        EN -> context.getString(R.string.lang_english)
        else -> context.getString(R.string.lang_system)
    }

    fun allOptions(): List<String> = listOf(SYSTEM, RU, KK, EN)
}
