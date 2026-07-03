package ru.toinet.android.receivers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import ru.toinet.android.R

class PresetWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        /* Widget start/stop code disabled
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_preset)
            
            // Intent for Toggle via Activity
            val toggleIntent = Intent(context, WidgetActionActivity::class.java).apply {
                action = ACTION_TOGGLE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val togglePendingIntent = PendingIntent.getActivity(
                context, 0, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_widget_toggle, togglePendingIntent)
            
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
        */
    }

    companion object {
        const val ACTION_TOGGLE = "ru.toinet.android.action.TOGGLE_WIDGET"
    }
}
