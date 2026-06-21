package ru.toinet.android.byedpi.utility

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.toinet.android.byedpi.data.DomainList
import java.io.File
import java.util.Locale

object DomainListUtils {
    private const val DOMAIN_LISTS_FILE = "domain_lists.json"

    private fun getDefaultActiveIds(lang: String): Set<String> = when (lang) {
        "tr" -> setOf("türkiye", "discord")
        else -> setOf("youtube", "googlevideo")
    }

    fun syncLists(context: Context) {
        val currentLists = getAllLists(context).toMutableList()

        val builtInMap = currentLists
            .filter { it.isBuiltIn }
            .associateBy { it.id }

        val assetFiles = context.assets.list("")?.filter {
            it.startsWith("proxytest_") && it.endsWith(".sites")
        } ?: emptyList()

        val newBuiltInIds = mutableSetOf<String>()

        for (assetFile in assetFiles) {
            val id = assetFile
                .removePrefix("proxytest_")
                .removeSuffix(".sites")

            newBuiltInIds.add(id)

            val domains = context.assets.open(assetFile)
                .bufferedReader()
                .useLines { it.toList() }
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val existing = builtInMap[id]

            when {
                existing == null -> {
                    currentLists.add(
                        DomainList(
                            id = id,
                            name = id.replaceFirstChar { it.uppercase() },
                            domains = domains,
                            isActive = id in getDefaultActiveIds(Locale.getDefault().language),
                            isBuiltIn = true
                        )
                    )
                }

                existing.isDeleted -> {
                    continue
                }

                existing.isModified -> {
                    continue
                }

                else -> {
                    val index = currentLists.indexOfFirst { it.id == id }

                    currentLists[index] = existing.copy(
                        domains = domains
                    )
                }
            }
        }

        currentLists.removeAll {
            it.isBuiltIn && it.id !in newBuiltInIds
        }

        saveLists(context, currentLists)
    }

    fun getAllLists(context: Context): MutableList<DomainList> {
        val listsFile = File(context.filesDir, DOMAIN_LISTS_FILE)

        if (!listsFile.exists()) {
            return mutableListOf()
        }

        return try {
            val json = listsFile.readText()
            val jsonArray = JSONArray(json)
            val lists = mutableListOf<DomainList>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val domainsArray = obj.getJSONArray("domains")
                val domains = mutableListOf<String>()
                for (j in 0 until domainsArray.length()) {
                    domains.add(domainsArray.getString(j))
                }
                lists.add(
                    DomainList(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", ""),
                        domains = domains,
                        isActive = obj.optBoolean("isActive", false),
                        isBuiltIn = obj.optBoolean("isBuiltIn", false),
                        isDeleted = obj.optBoolean("isDeleted", false),
                        isModified = obj.optBoolean("isModified", false)
                    )
                )
            }
            lists
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun getLists(context: Context): List<DomainList> {
        return getAllLists(context).filter { !it.isDeleted }
    }

    fun getActiveDomains(context: Context): List<String> {
        return getLists(context).filter { it.isActive }.flatMap { it.domains }.distinct()
    }

    fun saveLists(context: Context, lists: List<DomainList>) {
        val listsFile = File(context.filesDir, DOMAIN_LISTS_FILE)
        try {
            val jsonArray = JSONArray()
            for (list in lists) {
                val obj = JSONObject()
                obj.put("id", list.id)
                obj.put("name", list.name)
                val domainsArray = JSONArray()
                for (d in list.domains) {
                    domainsArray.put(d)
                }
                obj.put("domains", domainsArray)
                obj.put("isActive", list.isActive)
                obj.put("isBuiltIn", list.isBuiltIn)
                obj.put("isDeleted", list.isDeleted)
                obj.put("isModified", list.isModified)
                jsonArray.put(obj)
            }
            listsFile.writeText(jsonArray.toString())
        } catch (e: Exception) {}
    }

    fun addList(context: Context, name: String, domains: List<String>): Boolean {
        val lists = getAllLists(context).toMutableList()
        val id = name.lowercase().replace(" ", "_")

        val existing = lists.find { it.id == id }

        if (existing != null) {
            if (existing.isBuiltIn && existing.isDeleted) {
                val index = lists.indexOf(existing)

                lists[index] = existing.copy(
                    name = name,
                    domains = domains,
                    isDeleted = false,
                    isModified = true,
                    isActive = true
                )

                saveLists(context, lists)
                return true
            }

            return false
        }

        lists.add(
            DomainList(
                id = id,
                name = name,
                domains = domains,
                isActive = true,
                isBuiltIn = false
            )
        )

        saveLists(context, lists)
        return true
    }

    fun updateList(context: Context, id: String, name: String, domains: List<String>): Boolean {
        val lists = getAllLists(context).toMutableList()
        val index = lists.indexOfFirst { it.id == id }

        if (index == -1) return false

        val oldList = lists[index]

        lists[index] = oldList.copy(
            name = name,
            domains = domains,
            isModified = oldList.isBuiltIn
        )

        saveLists(context, lists)
        return true
    }

    fun toggleListActive(context: Context, id: String): Boolean {
        val lists = getAllLists(context).toMutableList()
        val index = lists.indexOfFirst { it.id == id }

        if (index == -1) return false

        val list = lists[index]
        lists[index] = list.copy(isActive = !list.isActive)

        saveLists(context, lists)
        return true
    }

    fun deleteList(context: Context, id: String): Boolean {
        val lists = getAllLists(context).toMutableList()
        val index = lists.indexOfFirst { it.id == id }

        if (index == -1) return false

        val list = lists[index]

        if (list.isBuiltIn) {
            lists[index] = list.copy(
                isDeleted = true,
                isActive = false
            )
        } else {
            lists.removeAt(index)
        }

        saveLists(context, lists)
        return true
    }

    fun resetLists(context: Context) {
        val file = File(context.filesDir, DOMAIN_LISTS_FILE)

        if (file.exists()) {
            file.delete()
        }

        syncLists(context)
    }
}
