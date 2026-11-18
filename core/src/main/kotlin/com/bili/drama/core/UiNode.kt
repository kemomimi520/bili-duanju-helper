package com.bili.drama.core

/**
 * Platform-independent UI node representation mapped from Android AccessibilityNodeInfo windows and nodes.
 */
data class RectBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isValid: Boolean get() = width > 0 && height > 0

    fun isOnScreen(screenWidth: Int, screenHeight: Int): Boolean {
        if (!isValid || screenWidth <= 0 || screenHeight <= 0) return false
        return centerX in 0 until screenWidth && centerY in 0 until screenHeight
    }
}

data class UiNode(
    val key: String,
    val viewId: String,
    val className: String,
    val text: String,
    val contentDesc: String,
    val bounds: RectBounds,
    val clickable: Boolean,
    val visible: Boolean,
    val enabled: Boolean
) {
    val displayLabel: String
        get() = text.ifBlank { contentDesc }.trim()
}

data class UiSnapshot(
    val packageName: String,
    val activityName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<UiNode>,
    val timestampMs: Long
) {
    fun firstById(viewId: String): UiNode? =
        nodes.firstOrNull {
            it.viewId == viewId && it.visible && it.bounds.isOnScreen(screenWidth, screenHeight)
        }

    fun firstLaidOutOnScreenById(viewId: String): UiNode? =
        nodes.firstOrNull {
            it.viewId == viewId && it.enabled && it.bounds.isOnScreen(screenWidth, screenHeight)
        }

    fun allById(viewId: String): List<UiNode> =
        nodes.filter {
            it.viewId == viewId && it.visible && it.bounds.isOnScreen(screenWidth, screenHeight)
        }

    fun allTexts(): List<String> =
        nodes.asSequence()
            .filter { it.visible && it.bounds.isOnScreen(screenWidth, screenHeight) }
            .map { it.displayLabel }
            .filter { it.isNotEmpty() }
            .toList()
}
