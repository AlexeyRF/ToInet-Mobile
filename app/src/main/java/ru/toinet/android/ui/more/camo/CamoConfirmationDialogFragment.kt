package ru.toinet.android.ui.more.camo

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import ru.toinet.android.BuildConfig
import ru.toinet.android.R
import ru.toinet.android.util.Prefs

class CamoConfirmationDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val mapping = CamoFragment.getCamoMapping(requireContext())
        val camoAppName = getString(args.getInt(BUNDLE_KEY_NAME))
        val altIconValue = args.getInt(BUNDLE_KEY_ALT_ICON_VAL)

        return AlertDialog.Builder(context)
            .setIcon(args.getInt(BUNDLE_KEY_IMAGE_ID))
            .setTitle(getString(R.string.app_icon_dialog_title, camoAppName))
            .setMessage(getString(R.string.app_icon_dialog_msg, camoAppName))
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                dismiss()
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                var key = camoAppName
                if (altIconValue != -1) key += altIconValue
                val activePackageName = mapping[key]!!
                Prefs.setCamoAppPackage(activePackageName)
                Prefs.camoAppDisplayName = camoAppName
                Prefs.camoAppAltIconIndex = altIconValue
                val disabledNames = mapping.values.filter { s -> s != activePackageName }
                AppIconNameChanger.changeAppIcon(requireActivity(), BuildConfig.APPLICATION_ID, activePackageName, disabledNames)
                activity?.finishAffinity()
            }
            .create()
    }

    companion object {
        const val BUNDLE_KEY_IMAGE_ID = "id"
        const val BUNDLE_KEY_NAME = "name"
        const val BUNDLE_KEY_ALT_ICON_VAL = "alt"
        const val TAG = "CamoConfirmDialog"
        fun newInstance(
            drawableId: Int,
            name: Int,
            altIconValue: Int
        ): CamoConfirmationDialogFragment = CamoConfirmationDialogFragment().apply {
            arguments = bundleOf(
                BUNDLE_KEY_IMAGE_ID to drawableId,
                BUNDLE_KEY_NAME to name,
                BUNDLE_KEY_ALT_ICON_VAL to altIconValue
            )
        }
    }
}