package ru.toinet.android.ui.connect

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import ru.toinet.android.R
import ru.toinet.android.ui.ToInetMenuAction
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.StringUtils

class ConnectMenuActionAdapter(context: Context, list: ArrayList<ToInetMenuAction>) :
    ArrayAdapter<ToInetMenuAction>(
        context, R.layout.action_list_view, list
    ) {

    private val layoutInflater =
        context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val returnView = convertView ?: layoutInflater.inflate(R.layout.action_list_view, null)
        getItem(position)?.let { model ->
            val imgView = returnView.findViewById<ImageView>(R.id.ivAction)
            val emojiContainer = returnView.findViewById<FrameLayout>(R.id.textContainer)
            val tvAction = returnView.findViewById<TextView>(R.id.tvEmoji)

            when (model.imgId) {
                0 -> {
                    imgView.visibility = View.GONE
                    val currentExit = Prefs.exitNodes?.replace("{", "")?.replace("}", "") ?: ""
                    if (currentExit.length == 2) tvAction.text =
                        StringUtils.convertCountryCodeToFlagEmoji(currentExit)
                    else tvAction.text = context.getString(R.string.globe)
                    emojiContainer.visibility = View.VISIBLE
                }

                else -> {
                    emojiContainer.visibility = View.GONE
                    imgView.visibility = View.VISIBLE
                    imgView.setImageResource(model.imgId)
                }
            }
            if (model.statusString?.isEmpty() == true)
                returnView.findViewById<TextView>(R.id.tvLabel).text = context.getString(model.textId)
            else
                returnView.findViewById<TextView>(R.id.tvLabel).text = model.statusString

            returnView.setOnClickListener { model.action() }
        }
        return returnView
    }
}
