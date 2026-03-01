package ru.toinet.android.ui.more.camo

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager

object AppIconNameChanger {
    fun changeAppIcon(
        activity: Activity,
        packageName: String,
        activePackageName: String,
        disabledPackageNames: List<String>
    ) {
        activity.packageManager.setComponentEnabledSetting(
            ComponentName(packageName, activePackageName),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
        )

        disabledPackageNames.forEach {
            try {
                activity.packageManager.setComponentEnabledSetting(
                    ComponentName(packageName, it),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}