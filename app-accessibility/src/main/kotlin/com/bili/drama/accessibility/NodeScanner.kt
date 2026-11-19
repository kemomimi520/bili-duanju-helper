package com.bili.drama.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.bili.drama.core.BiliNodes
import com.bili.drama.core.RectBounds
import com.bili.drama.core.UiNode
import com.bili.drama.core.UiSnapshot

/**
 * Converts live Android Accessibility windows & node trees into platform-independent UiSnapshot.
 * Does NOT call waitForIdle and uses zero screenshots/OCR.
 */
object NodeScanner {

    private const val SELF_PKG = "com.bili.drama.accessibility"
    private const val SYSTEM_UI_PKG = "com.android.systemui"

    data class ScanResult(
        val snapshot: UiSnapshot,
        val nodeRefs: Map<String, AccessibilityNodeInfo>
    )

    fun capture(
        windows: List<AccessibilityWindowInfo>,
        activeRoot: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        lastActivityName: String
    ): ScanResult {
        val uiNodes = mutableListOf<UiNode>()
        val nodeRefs = HashMap<String, AccessibilityNodeInfo>()
        var seq = 0

        // Sort windows by focus -> active -> layer so top foreground window/dialog determines pkg accurately
        val candidateWins = windows.asSequence()
            .filter {
                it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }
            .mapNotNull { win ->
                val r = win.root ?: return@mapNotNull null
                val rPkg = r.packageName?.toString().orEmpty()
                if (rPkg == SELF_PKG) return@mapNotNull null
                Triple(win, r, rPkg)
            }
            .sortedWith(
                compareByDescending<Triple<AccessibilityWindowInfo, AccessibilityNodeInfo, String>> { it.first.isFocused }
                    .thenByDescending { it.first.isActive }
                    .thenByDescending { it.first.layer }
            )
            .toList()

        // Prefer active system/OEM dialog package if it matches SYSTEM_CONFIRM_PACKAGES,
        // else topmost TYPE_APPLICATION window, avoiding transient systemui/self override.
        var pkg = candidateWins.firstOrNull { (win, _, rPkg) ->
            rPkg in BiliNodes.SYSTEM_CONFIRM_PACKAGES && (win.isFocused || win.isActive)
        }?.third.orEmpty()

        if (pkg.isBlank()) {
            pkg = candidateWins.firstOrNull { (win, _, rPkg) ->
                rPkg.isNotBlank() &&
                    rPkg != SYSTEM_UI_PKG &&
                    win.type == AccessibilityWindowInfo.TYPE_APPLICATION
            }?.third.orEmpty()
        }

        if (pkg.isBlank()) {
            val activePkg = activeRoot?.packageName?.toString().orEmpty()
            pkg = when {
                activePkg.isNotBlank() && activePkg != SELF_PKG && activePkg != SYSTEM_UI_PKG -> activePkg
                else -> candidateWins.firstOrNull { it.third.isNotBlank() }?.third.orEmpty().ifBlank { activePkg }
            }
        }

        val roots = mutableListOf<AccessibilityNodeInfo>()
        for ((_, root, rootPkg) in candidateWins) {
            if (rootPkg == SYSTEM_UI_PKG || rootPkg == SELF_PKG) continue
            if (roots.none { it == root }) {
                roots.add(root)
            }
        }
        val activePkg = activeRoot?.packageName?.toString().orEmpty()
        if (activeRoot != null &&
            activePkg != SELF_PKG &&
            activePkg != SYSTEM_UI_PKG &&
            roots.none { it == activeRoot }
        ) {
            roots.add(0, activeRoot)
        }

        for (root in roots) {
            traverse(root, uiNodes, nodeRefs, screenWidth, screenHeight) {
                seq += 1
                "n_$seq"
            }
        }

        return ScanResult(
            snapshot = UiSnapshot(
                packageName = pkg,
                activityName = lastActivityName,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                nodes = uiNodes,
                timestampMs = System.currentTimeMillis()
            ),
            nodeRefs = nodeRefs
        )
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        out: MutableList<UiNode>,
        refs: MutableMap<String, AccessibilityNodeInfo>,
        screenW: Int,
        screenH: Int,
        nextKey: () -> String
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val bounds = RectBounds(rect.left, rect.top, rect.right, rect.bottom)
        val onScreen = bounds.isOnScreen(screenW, screenH)
        val key = nextKey()
        refs[key] = node
        out.add(
            UiNode(
                key = key,
                viewId = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                text = node.text?.toString().orEmpty(),
                contentDesc = node.contentDescription?.toString().orEmpty(),
                bounds = bounds,
                clickable = node.isClickable,
                visible = node.isVisibleToUser && onScreen,
                enabled = node.isEnabled
            )
        )
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, out, refs, screenW, screenH, nextKey)
        }
    }
}
