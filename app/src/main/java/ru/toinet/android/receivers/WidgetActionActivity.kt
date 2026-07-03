package ru.toinet.android.receivers

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import ru.toinet.android.util.PresetManager
import ru.toinet.android.util.Prefs
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class WidgetActionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val action = intent?.action
        if (action == PresetWidgetProvider.ACTION_TOGGLE) {
            Toast.makeText(this, "Виджет отключен", Toast.LENGTH_SHORT).show()
            /* Widget start/stop code disabled
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                @Suppress("DEPRECATION")
                val isRunning = am.getRunningServices(Int.MAX_VALUE).any {
                    it.service.packageName == packageName && (
                        it.service.className == "ru.toinet.android.service.OrbotService" ||
                        it.service.className == "ru.toinet.android.byedpi.services.ByeDpiProxyService" ||
                        it.service.className == "ru.toinet.android.byedpi.services.ByeDpiVpnService"
                    )
                }

                if (isRunning) {
                    PresetManager.stopAll(this)
                    Toast.makeText(this, "Все прокси остановлены", Toast.LENGTH_SHORT).show()
                } else {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    
                    var isWifi = false
                    val networks = cm.allNetworks
                    for (network in networks) {
                        val caps = cm.getNetworkCapabilities(network)
                        if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                isWifi = true
                                break
                            }
                        }
                    }
                    
                    val presetJson = if (isWifi) Prefs.wifiPreset else Prefs.mobilePreset
                    
                    if (presetJson.isNotBlank()) {
                        PresetManager.applySnapshot(this, presetJson)
                        val modeName = if (isWifi) "Wi-Fi" else "Мобильный"
                        Toast.makeText(this, "$modeName режим запущен", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Пресет для этой сети не настроен", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("WidgetActionActivity", "Crash", e)
                Toast.makeText(this, "Ошибка виджета: ${e.message}", Toast.LENGTH_LONG).show()
            }
            */
        }
    }

    override fun onResume() {
        super.onResume()
        finish()
    }
}
