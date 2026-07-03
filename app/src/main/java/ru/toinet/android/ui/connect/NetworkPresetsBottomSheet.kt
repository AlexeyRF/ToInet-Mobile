package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import ru.toinet.android.R
import ru.toinet.android.ui.OrbotMenuAction
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.PresetManager
import android.widget.TextView

class NetworkPresetsBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.settings_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.tvTitle)?.text = "Сценарии сети"
        setupMenu(view)
    }

    private fun setupMenu(view: View) {
        val actions = arrayListOf(
            OrbotMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Сохранить текущие настройки для Wi-Fi"
            ) {
                Prefs.wifiPreset = PresetManager.createSnapshot(requireContext())
                Toast.makeText(requireContext(), "Пресет Wi-Fi сохранен", Toast.LENGTH_SHORT).show()
                dismiss()
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Сохранить текущие настройки для Мобильной сети"
            ) {
                Prefs.mobilePreset = PresetManager.createSnapshot(requireContext())
                Toast.makeText(requireContext(), "Пресет Мобильной сети сохранен", Toast.LENGTH_SHORT).show()
                dismiss()
            },
            OrbotMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Режим переключения: ${if (Prefs.networkSwitchMode == "smart_switch") "Мягкий (Умный)" else "Жесткий (Перезапуск)"}"
            ) {
                Prefs.networkSwitchMode = if (Prefs.networkSwitchMode == "smart_switch") "restart_all" else "smart_switch"
                setupMenu(view)
            }
        )
        val lvSettings = view.findViewById<ListView>(R.id.lvSettings)
        lvSettings.adapter = ConnectMenuActionAdapter(requireContext(), actions)
    }
}
