package app.dereel.nativeapp

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.dereel.core.nativeapp.Bounds
import app.dereel.core.nativeapp.UiNode

/**
 * Adapts an accessibility node to the detector interface. It deliberately exposes no text or
 * content description, so detectors cannot read message content (SEC-03).
 */
class AccessibilityNodeUiNode(private val node: AccessibilityNodeInfo) : UiNode {
    override val viewId: String? get() = node.viewIdResourceName
    override val isVisibleToUser: Boolean get() = node.isVisibleToUser
    override val isSelected: Boolean get() = node.isSelected
    override val bounds: Bounds
        get() {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            return Bounds(rect.left, rect.top, rect.right, rect.bottom)
        }
    override val childCount: Int get() = node.childCount
    override fun child(index: Int): UiNode? = try {
        node.getChild(index)?.let(::AccessibilityNodeUiNode)
    } catch (e: IllegalStateException) {
        null
    }

    /** One IPC: the target app searches its own hierarchy (needs flagReportViewIds). */
    override fun findByViewId(fullId: String): List<UiNode> = try {
        node.findAccessibilityNodeInfosByViewId(fullId).map(::AccessibilityNodeUiNode)
    } catch (e: IllegalStateException) {
        emptyList()
    }
}
