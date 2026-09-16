package com.novadrive.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AmapAutoPickService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            val now = System.currentTimeMillis()
            val req = NavigationAutoPick.active(now) ?: return
            if (event.packageName != "com.autonavi.minimap") return
            if (NavigationAutoPick.debounced(now)) return
            val root = rootInActiveWindow ?: return
            when (req.stage) {
                NavigationAutoPick.Stage.PICK_DESTINATION -> {
                    val nodes = mutableListOf<AccessibilityNodeInfo>()
                    collectTextNodes(root, nodes)
                    val texts = nodes.map { it.text?.toString().orEmpty() }
                    val idx = NavigationAutoPick.pickIndex(texts, req.label) ?: return
                    if (click(nodes[idx])) NavigationAutoPick.advance(now)
                }
                NavigationAutoPick.Stage.START_NAVIGATION -> {
                    val nodes = mutableListOf<AccessibilityNodeInfo>()
                    collectTextNodes(root, nodes)
                    val target = nodes.firstOrNull {
                        NavigationAutoPick.isStartButton(it.text?.toString().orEmpty())
                    } ?: return
                    if (click(target)) NavigationAutoPick.clear()
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {}

    private fun collectTextNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.text?.toString()?.trim().orEmpty().isNotEmpty()) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextNodes(child, out)
        }
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        return try {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    break
                }
                current = current.parent
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }
}
