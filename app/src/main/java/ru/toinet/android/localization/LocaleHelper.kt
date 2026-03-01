package ru.toinet.android.localization

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import ru.toinet.android.util.Prefs
import java.util.*

/**
 * This class is used to change your application locale and persist this change for the next time
 * that your app is going to be used.
 *
 *
 * You can also change the locale of your application on the fly by using the setLocale method.
 *
 *
 * Created by gunhansancar on 07/10/15.
 * https://gunhansancar.com/change-language-programmatically-in-android/
 */
object LocaleHelper {
    fun onAttach(context: Context): Context = setLocale(context, Prefs.defaultLocale)

    private fun setLocale(context: Context, language: String): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context
        }
        Prefs.defaultLocale = language
        return updateResources(context, language)
    }

    private fun updateResources(context: Context, locale: String): Context {

        var language = locale
        var region = ""

        if (language.contains("_")) {
            val parts = locale.split("_")
            language = parts[0]
            region = parts[1]
        }

        val localeObj = Languages.buildLocaleForLanguage(language, region)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(localeObj))
        Locale.setDefault(localeObj)
        val configuration = context.resources.configuration
        configuration.setLocale(localeObj)
        configuration.setLayoutDirection(localeObj)
        return context.createConfigurationContext(configuration)
    }
}