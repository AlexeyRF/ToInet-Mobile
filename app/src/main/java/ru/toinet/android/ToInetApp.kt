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
// import ru.toinet.android.tgws.UniversalTgProxy
import ru.toinet.android.util.NetworkSwitchListener
import ru.toinet.android.util.NetworkBlackoutMonitor

import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import java.util.Locale
import java.io.File

class ToInetApp : Application() {


    override fun onCreate() {
        super.onCreate()
        
        Prefs.setContext(applicationContext)
        
        // Removed revokeSelfPermissionOnKill block that was resetting notifications

        // set state dir for IPtProxy
        try {
            stateLocation = cacheDir.path
        } catch (_ : Exception) {
            Log.e("ToInetApp", "Couldn't set PT state dir")
        }

        LocaleHelper.onAttach(applicationContext)

        Languages.setup(ToInetActivity::class.java, R.string.menu_settings)

        setLocale()

        // Only run main UI/process initialization in the main application process
        if (!isMainProcess()) {
            Log.d("ToInetApp", "Secondary process initialized, skipping background workers and main UI setup.")
            return
        }

        AppCompatDelegate.setDefaultNightMode(Prefs.themeMode)
        if (Prefs.useDynamicColors) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        
        NetworkSwitchListener.register(this)
        NetworkBlackoutMonitor.start(this)
        try {
            ru.toinet.android.easyvpn.EasyVpnUpdateWorker.schedule(this)
        } catch (e: Exception) {
            Log.e("ToInetApp", "Failed to schedule EasyVpnUpdateWorker", e)
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                super.onStop(owner)
                if (!isAuthenticationPromptOpenLegacyFlag)
                    shouldRequestAuthentication = true
            }

        })

        // this code only runs on first install and app updates
        if (Prefs.currentVersionForUpdate < BuildConfig.VERSION_CODE) {
            Prefs.currentVersionForUpdate = BuildConfig.VERSION_CODE
            // don't do anything resource intensive here, instead set a flag to do the task later

            // tell ToInetService it needs to reinstall geoip
            Prefs.isGeoIpReinstallNeeded = true

            // Clean cached strategies so updated bundled assets with Zapret strategies are used
            try {
                File(filesDir, "proxytest_strategies.list").delete()
            } catch (ignored: Exception) {}
        }
    }

    private fun isMainProcess(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageName == Application.getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName == packageName
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
        // see https://github.com/guardianproject/toinet-android/issues/1340
        var isAuthenticationPromptOpenLegacyFlag: Boolean = false
        fun resetLockFlags() {
            shouldRequestAuthentication = true
            isAuthenticationPromptOpenLegacyFlag = false
        }
    }
}
