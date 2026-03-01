package ru.toinet.android.ui

/**
 * Generic data class for a clickable menu item that has text and a graphic
 */
data class OrbotMenuAction(
    val textId: Int,
    val imgId: Int,
    var roundImageCorner: Boolean = false,
    val removeTint: Boolean = false,
    var backgroundColor: Int? = null,
    val statusString: String? = "",
    val action: () -> Unit
)

