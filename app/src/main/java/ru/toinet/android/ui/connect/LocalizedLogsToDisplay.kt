package ru.toinet.android.ui.connect

import android.content.Context
import android.widget.TextView
import ru.toinet.android.R

object LocalizedLogsToDisplay {

    private val systemLogsToDisplay = mapOf(
        "(handshake)" to R.string.status_handshake,
        "(handshake_done)" to R.string.status_handshake_done,
        "(circuit_create)" to R.string.status_circuit_create,
        "(done)" to R.string.status_done,
        "(conn_pt)" to R.string.status_conn_pt,
        "(conn_done_pt)" to R.string.status_conn_done_pt,
        "(conn_done)" to R.string.status_conn_done,
        "(onehop_create)" to R.string.status_onehop_create,
        "(enough_dirinfo)" to R.string.status_enough_dirinfo,
        "(requesting_status)" to R.string.status_requesting_status,
        "(loading_status)" to R.string.status_loading_status,
        "(loading_keys)" to R.string.status_loading_status,
        "(requesting_descriptors)" to R.string.status_requesting_descriptors,
        "(loading_descriptors)" to R.string.status_loading_descriptors,
    )

    private val localizedLogsToDisplay = listOf(R.string.status_connected_control_port)

    fun updateLabelIfDisplayed(logline: String, label: TextView, context: Context?) {
        if (logline.isBlank()) return
        systemLogsToDisplay.keys.forEach { key ->
            if (logline.contains(key)) {
                label.setText(systemLogsToDisplay[key]!!)
                return
            }
        }
        if (context == null) return
        localizedLogsToDisplay.forEach {
            val str = context.getString(it)
            if (logline.contains(str)) {
                label.text = str
            }
        }
    }
}