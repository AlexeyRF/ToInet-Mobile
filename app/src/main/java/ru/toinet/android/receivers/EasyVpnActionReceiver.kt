package ru.toinet.android.receivers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import ru.toinet.android.ToInetActivity
import ru.toinet.android.util.Prefs
import ru.toinet.android.vpn.data.Mode
import ru.toinet.android.vpn.services.ServiceManager

class EasyVpnActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_CONNECT_EASYVPN) {
            Prefs.vpnProvider = "easyvpn"
            Prefs.isGlobalVpnEnabled = true

            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                val activityIntent = Intent(context, ToInetActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("start_vpn", true)
                }
                context.startActivity(activityIntent)
            } else {
                ServiceManager.start(context, Mode.VPN)
                Toast.makeText(context, "Включение EasyVPN...", Toast.LENGTH_SHORT).show()
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID_EASYVPN_ALERT)
        }
    }

    companion object {
        const val ACTION_CONNECT_EASYVPN = "ru.toinet.android.action.CONNECT_EASYVPN"
        const val NOTIFICATION_ID_EASYVPN_ALERT = 8842
    }
}
