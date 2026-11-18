package com.bili.drama.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.bili.drama.core.RectBounds
import com.bili.drama.core.UiNode
import com.bili.drama.core.UiSnapshot

/**
 * Converts live Android Accessibility windows & node trees into platform-independent UiSnapshot.
 * Does NOT call waitForIdle and uses zero screenshots/OCR.
 */
object NodeScanner {

    data class ScanResult(
        val snapshot: UiSnapshot,
        val nodeRefs: Map<String, AccessibilityNodeInfo>
    )

    fun capture(
        windows: List<android.view.accessibility.AccessibilityWindowInfo>,
        activeRoot: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        lastActivityName: String
    ): ScanResult {
        val uiNodes = mutableListOf<UiNode>()
        val nodeRefs = HashMap<String, AccessibilityNodeInfo>()
        var pkg = activeRoot?.packageName?.toString().orEmpty()
        var seq = 0

        val roots = mutableListOf<AccessibilityNodeInfo>()
        if (activeRoot != null) {
            roots.add(activeRoot)
        }
        for (win in windows) {
            if (win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                continue
            }
            val r = win.root ?: continue
            if (r != activeRoot) {
                roots.add(r)
            }
        }

        for (root in roots) {
            val rootPkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank() && rootPkg.isNotBlank() && rootPkg != "com.android.systemui") {
                pkg = rootPkg
            }
            // Skip scanning our own overlay and systemui notification banners
            if (rootPkg == "com.bili.drama.accessibility" || rootPkg == "com.android.systemui") continue
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
