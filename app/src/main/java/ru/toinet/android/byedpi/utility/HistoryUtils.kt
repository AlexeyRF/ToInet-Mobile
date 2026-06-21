package ru.toinet.android.byedpi.utility

import android.content.Context
import android.content.SharedPreferences
import ru.toinet.android.byedpi.data.Command
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit

class HistoryUtils(context: Context) {

    private val context = context.applicationContext
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("byedpi", Context.MODE_PRIVATE)
    private val historyKey = "byedpi_command_history"
    private val maxHistorySize = 40

    fun addCommand(command: String) {
        if (command.isBlank()) return

        val history = getHistory().toMutableList()
        val unpinned = history.filter { !it.pinned }
        val search = history.find { it.text == command }

        if (search == null) {
            history.add(0, Command(command))
            if (history.size > maxHistorySize) {
                if (unpinned.isNotEmpty()) {
                    history.remove(unpinned.last())
                }
            }
        }

        saveHistory(history)
    }

    fun pinCommand(command: String) {
        val history = getHistory().toMutableList()
        history.find { it.text == command }?.pinned = true
        saveHistory(history)
    }

    fun unpinCommand(command: String) {
        val history = getHistory().toMutableList()
        history.find { it.text == command }?.pinned = false
        saveHistory(history)
    }

    fun deleteCommand(command: String) {
        val history = getHistory().toMutableList()
        history.removeAll { it.text == command }
        saveHistory(history)
    }

    fun renameCommand(command: String, newName: String) {
        val history = getHistory().toMutableList()
        history.find { it.text == command }?.name = newName
        saveHistory(history)
    }

    fun editCommand(command: String, newText: String) {
        val history = getHistory().toMutableList()
        history.find { it.text == command }?.text = newText
        saveHistory(history)
    }

    fun getHistory(): List<Command> {
        val historyJson = sharedPreferences.getString(historyKey, null)
        if (historyJson != null) {
            try {
                val array = JSONArray(historyJson)
                val list = mutableListOf<Command>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(Command(
                        text = obj.optString("text", ""),
                        pinned = obj.optBoolean("pinned", false),
                        name = if (obj.has("name")) obj.getString("name") else null
                    ))
                }
                return list
            } catch (e: Exception) {
                return emptyList()
            }
        }
        return emptyList()
    }

    fun saveHistory(history: List<Command>) {
        try {
            val array = JSONArray()
            for (c in history) {
                val obj = JSONObject()
                obj.put("text", c.text)
                obj.put("pinned", c.pinned)
                if (c.name != null) {
                    obj.put("name", c.name)
                }
                array.put(obj)
            }
            sharedPreferences.edit { putString(historyKey, array.toString()) }
        } catch (e: Exception) {}
    }

    fun clearAllHistory() {
        saveHistory(emptyList())
    }

    fun clearUnpinnedHistory() {
        val history = getHistory().filter { it.pinned }
        saveHistory(history)
    }
}
