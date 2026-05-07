package ru.toinet.android.ui.more

import IPtProxy.IPtProxy
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import ru.toinet.android.BuildConfig
import ru.toinet.android.R
import ru.toinet.android.util.DiskUtils
import org.torproject.jni.TorService
import java.io.IOException

class AboutDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "AboutDialogFragment"
        const val VERSION = BuildConfig.VERSION_NAME
        private const val BUNDLE_KEY_TV_ABOUT_TEXT = "about_tv_txt"
    }

    private lateinit var tvAbout: TextView

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view: View? = activity?.layoutInflater?.inflate(R.layout.layout_about, null)

        var buildAboutText = true

        savedInstanceState?.getString(BUNDLE_KEY_TV_ABOUT_TEXT)?.let {
            buildAboutText = false
            tvAbout.text = it
        }

        if (buildAboutText) {
            try {
                val equalsBlockRegex = Regex("={3,}")
                var aboutText = DiskUtils.readFileFromAssets("LICENSE", requireContext())
                aboutText = aboutText.replace(equalsBlockRegex, "")

                val spannableAboutText = SpannableStringBuilder(aboutText)
                spannableAboutText.setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    aboutText.indexOf("\n"),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                tvAbout.text = spannableAboutText
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        return AlertDialog.Builder(context, R.style.OrbotDialogTheme)
            .setTitle(getString(R.string.menu_about))
            .setView(view)
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(BUNDLE_KEY_TV_ABOUT_TEXT, tvAbout.text.toString())
    }
}