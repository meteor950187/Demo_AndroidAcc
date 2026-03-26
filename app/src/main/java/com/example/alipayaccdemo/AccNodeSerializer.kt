package com.example.alipayaccdemo

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.Serializable

/**
 * 完整序列化 AccessibilityNodeInfo 樹
 * - 不限制深度
 * - 保留所有節點（包含不可見的）
 * - 可直接輸出為 JSON
 *
 * ⚠️ 注意：此版本會遍歷整棵 UI Tree，請務必在背景執行緒執行。
 */
class AccNodeSerializer {

    // -----------------------------
    // 資料結構定義
    // -----------------------------
    @Serializable
    data class RectData(val left: Int, val top: Int, val right: Int, val bottom: Int)

    @Serializable
    data class ActionData(val id: Int, val label: String? = null)

    @Serializable
    data class RangeData(val min: Float, val max: Float, val current: Float, val type: Int)

    @Serializable
    data class
    CollectionData(
        val rowCount: Int, val columnCount: Int,
        val hierarchical: Boolean, val selectionMode: Int
    )

    @Serializable
    data class CollectionItemData(
        val rowIndex: Int, val rowSpan: Int,
        val columnIndex: Int, val columnSpan: Int,
        val heading: Boolean, val selected: Boolean
    )

    @Serializable
    data class NodeSnapshot(
        // 結構資訊
        val className: String? = null,
        val packageName: String? = null,
        val viewId: String? = null,
        val childCount: Int = 0,
        val boundsInScreen: RectData? = null,
        val boundsInParent: RectData? = null,

        // 文字與描述
        val text: String? = null,
        val contentDescription: String? = null,
        val hintText: String? = null,
        val paneTitle: String? = null,
        val tooltipText: String? = null,
        val roleDescription: String? = null,

        // 狀態旗標
        val clickable: Boolean = false,
        val longClickable: Boolean = false,
        val focusable: Boolean = false,
        val focused: Boolean = false,
        val selected: Boolean = false,
        val checkable: Boolean = false,
        val checked: Boolean = false,
        val enabled: Boolean = true,
        val visibleToUser: Boolean = true,
        val accessibilityFocused: Boolean = false,
        val password: Boolean = false,
        val scrollable: Boolean = false,
        val editable: Boolean? = null,
        val dismissable: Boolean? = null,
        val multiLine: Boolean? = null,

        // 輸入與文字屬性
        val inputType: Int? = null,
        val textSelectionStart: Int? = null,
        val textSelectionEnd: Int? = null,
        val maxTextLength: Int? = null,

        // 結構性資訊
        val rangeInfo: RangeData? = null,
        val collectionInfo: CollectionData? = null,
        val collectionItemInfo: CollectionItemData? = null,



        // 子節點
        val children: List<NodeSnapshot> = emptyList()
    )

    // -----------------------------
    // 公開方法
    // -----------------------------
    fun serialize(root: AccessibilityNodeInfo?): NodeSnapshot? = traverse(root)

    // -----------------------------
    // 遞迴清洗核心
    // -----------------------------
    private fun traverse(node: AccessibilityNodeInfo?): NodeSnapshot? {
        if (node == null) return null
        try {
            val pkg = node.packageName?.toString()
            val cls = node.className?.toString()
            val id = node.viewIdResourceName

            val rectScreen = Rect().apply { node.getBoundsInScreen(this) }
            val rectParent = Rect().apply { node.getBoundsInParent(this) }

            val snapshot = NodeSnapshot(
                className = cls,
                packageName = pkg,
                viewId = id,
                childCount = node.childCount,
                boundsInScreen = RectData(rectScreen.left, rectScreen.top, rectScreen.right, rectScreen.bottom),
                boundsInParent = RectData(rectParent.left, rectParent.top, rectParent.right, rectParent.bottom),
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                hintText = safeCall { node.hintText?.toString() },
                paneTitle = safeCall { node.paneTitle?.toString() },
                tooltipText = safeCall { node.tooltipText?.toString() },
                roleDescription = safeCall {
                    node.extras?.getCharSequence("AccessibilityNodeInfo.roleDescription")?.toString()
                },
                clickable = node.isClickable,
                longClickable = node.isLongClickable,
                focusable = node.isFocusable,
                focused = node.isFocused,
                selected = node.isSelected,
                checkable = node.isCheckable,
                checked = node.isChecked,
                enabled = node.isEnabled,
                visibleToUser = safeCall { node.isVisibleToUser } ?: true,
                accessibilityFocused = node.isAccessibilityFocused,
                password = node.isPassword,
                scrollable = node.isScrollable,
                editable = safeCall { node.isEditable },
                dismissable = safeCall { node.isDismissable },

                multiLine = safeCall { node.isMultiLine },
                inputType = safeCall { node.inputType },
                textSelectionStart = safeCall { node.textSelectionStart },
                textSelectionEnd = safeCall { node.textSelectionEnd },
                maxTextLength = safeCall { node.maxTextLength },
                rangeInfo = node.rangeInfo?.let {
                    RangeData(it.min, it.max, it.current, it.type)
                },
                collectionInfo = node.collectionInfo?.let {
                    CollectionData(it.rowCount, it.columnCount, it.isHierarchical, it.selectionMode)
                },
                collectionItemInfo = node.collectionItemInfo?.let {
                    CollectionItemData(it.rowIndex, it.rowSpan, it.columnIndex, it.columnSpan, it.isHeading, it.isSelected)
                },
                children = buildList {
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i)
                        val mapped = try {
                            traverse(child)
                        } finally {
                            try { child?.recycle() } catch (_: Exception) {}
                        }
                        if (mapped != null) add(mapped)
                    }
                }
            )

            return snapshot
        } catch (_: Throwable) {
            return null
        }
    }

    private inline fun <T> safeCall(block: () -> T): T? =
        try { block() } catch (_: Throwable) { null }
}
