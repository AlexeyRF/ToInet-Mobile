package ru.toinet.android.ui.more

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import ru.toinet.android.R
import ru.toinet.android.service.ToInetConstants
import ru.toinet.android.util.showToast
import ru.toinet.android.ui.ToInetBottomSheetDialogFragment

class LogBottomSheet : ToInetBottomSheetDialogFragment() {

    private lateinit var tvLog: TextView
    private var buffer = StringBuffer()

    private val logReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val logLine = intent?.getStringExtra(ToInetConstants.LOCAL_EXTRA_LOG)
            if (!logLine.isNullOrBlank()) {
                appendLog(logLine)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.log_bottom_sheet, container, false)
        tvLog = v.findViewById(R.id.toinetLog)
        val history = ru.toinet.android.util.NotificationLogger.getAllLogs()
        if (history.isNotEmpty() && buffer.isEmpty()) {
            buffer.append(history).append("\n")
        }
        tvLog.text = buffer.toString()

        v.findViewById<FloatingActionButton>(R.id.btnCopyLog).setOnClickListener {
            val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip: ClipData = ClipData.newPlainText("log", tvLog.text)
            clipboard.setPrimaryClip(clip)
            v.context.showToast(R.string.log_copied)
        }
        return v
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ToInetConstants.LOCAL_ACTION_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(logReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            requireContext().unregisterReceiver(logReceiver)
        } catch (_: Exception) {}
    }

    fun appendLog(logLine: String) {
        if (this::tvLog.isInitialized) {
            tvLog.append("$logLine\n")
        }
        buffer.append("$logLine\n")
    }

}