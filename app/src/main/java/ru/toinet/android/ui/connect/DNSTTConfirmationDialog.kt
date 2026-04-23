package ru.toinet.android.ui.connect

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import ru.toinet.android.R

class DNSTTConfirmationDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireActivity())
            .setTitle(R.string.limit_dns_tunnel_use)
            .setMessage(R.string.dns_tunnel_usage_description)
            .setIconAttribute(android.R.attr.alertDialogIcon)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.connect) { _, _ ->
                dismiss()
                val parent = requireActivity().supportFragmentManager.findFragmentByTag(
                    "ConfigConnectionBttmSheet" // Используем строковое значение вместо TAG
                ) as ConfigConnectionBottomSheet
                parent.closeAndConnect()
            }
            .create()
    }

    companion object {
        const val TAG = "DNSTTConfirm"
    }

}