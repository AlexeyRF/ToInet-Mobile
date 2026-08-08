package ru.toinet.android

import android.app.Application
import android.content.res.Configuration
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ru.toinet.android.localization.Languages
import ru.toinet.android.localization.LocaleHelper
import ru.toinet.android.service.circumvention.Transport.Companion.stateLocation
import ru.toinet.android.util.Prefs
import ru.toinet.android.tgws.UniversalTgProxy
import ru.toinet.android.util.NetworkSwitchListener

import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class OrbotApp : Application() {


    override fun onCreate() {
        super.onCreate()
        
        Prefs.setContext(applicationContext)
        
        // Removed revokeSelfPermissionOnKill block that was resetting notifications

        AppCompatDelegate.setDefaultNightMode(Prefs.themeMode)
        if (Prefs.useDynamicColors) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        
        /*
        if (Prefs.universalTgProxyEnabled) {
            UniversalTgProxy.start()
        }
        */
        

        // set state dir for IPtProxy
        try {
            stateLocation = cacheDir.path
        } catch (_ : Exception) {
            Log.e("OrbotApp", "Couldn't set PT state dir")
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                super.onStop(owner)
                if (!isAuthenticationPromptOpenLegacyFlag)
                    shouldRequestAuthentication = true
            }

        })

        LocaleHelper.onAttach(applicationContext)

        Languages.setup(OrbotActivity::class.java, R.string.menu_settings)

        setLocale()

        // this code only runs on first install and app updates
        if (Prefs.currentVersionForUpdate < BuildConfig.VERSION_CODE) {
            Prefs.currentVersionForUpdate = BuildConfig.VERSION_CODE
            // don't do anything resource intensive here, instead set a flag to do the task later

            // tell OrbotService it needs to reinstall geoip
            Prefs.isGeoIpReinstallNeeded = true
        }

        // Check and update proxytest_strategies.list from github
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val lastUpdate = Prefs.lastStrategiesUpdate
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 24 * 60 * 60 * 1000L) {
                    val url = URL("https://raw.githubusercontent.com/romanvht/ByeByeDPI/master/app/src/main/assets/proxytest_strategies.list")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    if (connection.responseCode == 200) {
                        val content = connection.inputStream.bufferedReader().readText()
                        if (content.isNotBlank()) {
                            val file = File(filesDir, "proxytest_strategies.list")
                            file.writeText(content)
                            Prefs.lastStrategiesUpdate = now
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("OrbotApp", "Failed to update strategies list", e)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        setLocale()
    }

    fun setLocale() {
        val appLocale = Prefs.defaultLocale
        val systemLoc = Locale.getDefault().language
        if (appLocale != systemLoc) {
            Languages.setLanguage(this, appLocale, true)
        }
    }

    companion object {
        var shouldRequestAuthentication: Boolean = true
        // see https://github.com/guardianproject/orbot-android/issues/1340
        var isAuthenticationPromptOpenLegacyFlag: Boolean = false
        fun resetLockFlags() {
            shouldRequestAuthentication = true
            isAuthenticationPromptOpenLegacyFlag = false
        }
    }
}
