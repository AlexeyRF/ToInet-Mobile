package ru.toinet.android.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import ru.toinet.android.R
import ru.toinet.android.ui.ToInetMenuAction
import ru.toinet.android.util.Prefs
import ru.toinet.android.util.PresetManager

class NetworkPresetsBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.network_presets_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMenu(view)
    }

    private fun setupMenu(view: View) {
        val actions = arrayListOf<ToInetMenuAction>()

        actions.add(
            ToInetMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Сохранить профиль для Wi-Fi\n(${if (Prefs.wifiPreset.isNotBlank()) "✓ Сохранен" else "✗ Не сохранен"})"
            ) {
                Prefs.wifiPreset = PresetManager.createSnapshot(requireContext())
                Toast.makeText(requireContext(), "Пресет Wi-Fi сохранен", Toast.LENGTH_SHORT).show()
                setupMenu(view)
            }
        )

        actions.add(
            ToInetMenuAction(
                0,
                R.drawable.ic_settings_gear,
                statusString = "Сохранить профиль для Мобильной сети\n(${if (Prefs.mobilePreset.isNotBlank()) "✓ Сохранен" else "✗ Не сохранен"})"
            ) {
                Prefs.mobilePreset = PresetManager.createSnapshot(requireContext())
                Toast.makeText(requireContext(), "Пресет Мобильной сети сохранен", Toast.LENGTH_SHORT).show()
                setupMenu(view)
            }
        )

        actions.add(
            ToInetMenuAction(
                0,
                R.drawable.ic_autorenew,
                statusString = "Режим переключения: ${if (Prefs.networkSwitchMode == "smart_switch") "Мягкий (Умный)" else "Жесткий (Перезапуск)"}\n(Нажмите для смены)"
            ) {
                Prefs.networkSwitchMode = if (Prefs.networkSwitchMode == "smart_switch") "restart_all" else "smart_switch"
                setupMenu(view)
            }
        )

        if (Prefs.wifiPreset.isNotBlank()) {
            actions.add(
                ToInetMenuAction(
                    0,
                    R.drawable.ic_refresh,
                    statusString = "Применить пресет Wi-Fi прямо сейчас"
                ) {
                    PresetManager.applySnapshot(requireContext(), Prefs.wifiPreset)
                    Toast.makeText(requireContext(), "Применяется пресет Wi-Fi...", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            )
        }

        if (Prefs.mobilePreset.isNotBlank()) {
            actions.add(
                ToInetMenuAction(
                    0,
                    R.drawable.ic_refresh,
                    statusString = "Применить пресет Мобильной сети прямо сейчас"
                ) {
                    PresetManager.applySnapshot(requireContext(), Prefs.mobilePreset)
                    Toast.makeText(requireContext(), "Применяется пресет Мобильной сети...", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            )
        }

        if (Prefs.wifiPreset.isNotBlank() || Prefs.mobilePreset.isNotBlank()) {
            actions.add(
                ToInetMenuAction(
                    0,
                    R.drawable.ic_close,
                    statusString = "Сбросить сохраненные пресеты"
                ) {
                    Prefs.wifiPreset = ""
                    Prefs.mobilePreset = ""
                    Toast.makeText(requireContext(), "Пресеты сброшены", Toast.LENGTH_SHORT).show()
                    setupMenu(view)
                }
            )
        }

        val lvSettings = view.findViewById<ListView>(R.id.lvSettings)
        lvSettings.adapter = ConnectMenuActionAdapter(requireContext(), actions)
    }

    companion object {
        const val TAG = "NetworkPresetsBottomSheet"
    }
}
