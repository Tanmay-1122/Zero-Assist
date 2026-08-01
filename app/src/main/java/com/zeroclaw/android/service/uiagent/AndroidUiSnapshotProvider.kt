/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

@file:Suppress("ReturnCount")

package com.zeroclaw.android.service.uiagent

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Android Accessibility-backed observation provider.
 *
 * This class intentionally only reads already-available accessibility roots/windows. It does not
 * request permissions, dispatch actions, or wait for UI state changes.
 *
 * **Performance optimizations:**
 * - Reduced max tree depth from 30 to 18 levels
 * - Prunes invisible nodes early to skip unnecessary traversal
 * - Limits total node count to prevent excessive processing
 * - Adds performance logging for monitoring
 */
class AndroidUiSnapshotProvider(
    private val rootProvider: () -> AccessibilityNodeInfo?,
    private val windowsProvider: () -> List<AccessibilityWindowInfo> = { emptyList() },
    private val foregroundTracker: ForegroundWindowTracker = InMemoryForegroundWindowTracker(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : UiSnapshotProvider {
    override fun currentSnapshot(): UiSnapshot? {
        val startTime = System.currentTimeMillis()
        val capturedAtEpochMs = clock()
        val windows = windowsProvider()
        val windowRoots =
            windows.mapNotNull { window ->
                window.root?.let { root ->
                    WindowRoot(
                        root = root,
                        title = window.title?.toString(),
                    )
                }
            }
        val activeRoot = rootProvider()
        val trackedForegroundPackageName = foregroundTracker.state.value.packageName
        val trackedWindowRoot =
            trackedForegroundPackageName
                ?.let { packageName ->
                    windowRoots.firstOrNull { windowRoot ->
                        windowRoot.root.packageName?.toString() == packageName
                    }
                }
        val systemUiWindowRoot =
            windowRoots.firstOrNull { windowRoot ->
                windowRoot.root.packageName?.toString().isSystemUiPackage()
            }
        val activeWindowTitle =
            windowRoots.firstOrNull { windowRoot -> windowRoot.root === activeRoot }?.title
        val fallbackWindowRoot = trackedWindowRoot ?: windowRoots.firstOrNull()
        val root =
            when {
                activeRoot.isInputMethodRoot() && trackedWindowRoot != null ->
                    trackedWindowRoot.root

                else ->
                    activeRoot ?: systemUiWindowRoot?.root ?: fallbackWindowRoot?.root ?: return null
            }
        val windowTitle =
            windowRoots.firstOrNull { windowRoot -> windowRoot.root === root }?.title
                ?: fallbackWindowRoot?.takeIf { activeRoot == null }?.title
        val foreground =
            foregroundTracker.markRootReady(
                packageName = root.packageName?.toString(),
                windowTitle = windowTitle,
                timestampMs = capturedAtEpochMs,
            )

        val snapshot = UiSnapshotMapper.toSnapshot(
            RawUiSnapshot(
                roots = listOf(root.toRawUiNode()),
                capturedAtEpochMs = capturedAtEpochMs,
                foregroundPackageName = foreground.packageName,
                foregroundWindowTitle = foreground.windowTitle,
            ),
        )

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "UI snapshot created in ${duration}ms")

        return snapshot
    }

    companion object {
        private const val TAG = "AndroidUiSnapshot"
    }
}

private data class WindowRoot(
    val root: AccessibilityNodeInfo,
    val title: String?,
)

private fun AccessibilityNodeInfo?.isInputMethodRoot(): Boolean {
    val packageName = this?.packageName?.toString()
    return packageName.isInputMethodPackage()
}

fun AccessibilityNodeInfo.toRawUiNode(maxDepth: Int = DEFAULT_MAX_NODE_DEPTH): RawUiNode =
    toRawUiNode(depth = 0, maxDepth = maxDepth, nodeCounter = NodeCounter())

/**
 * Tracks the total number of nodes processed to enforce limits.
 */
private class NodeCounter {
    var count = 0
    fun increment(): Boolean {
        count++
        return count <= MAX_TOTAL_NODES
    }
}

private fun AccessibilityNodeInfo.toRawUiNode(
    depth: Int,
    maxDepth: Int,
    nodeCounter: NodeCounter,
): RawUiNode {
    // Early termination if we've hit depth or node count limits
    if (depth >= maxDepth || !nodeCounter.increment()) {
        return createEmptyNode()
    }

    // Skip invisible nodes early to avoid unnecessary processing
    if (!isVisibleToUser) {
        return createEmptyNode()
    }

    val bounds = Rect()
    getBoundsInScreen(bounds)

    // Skip nodes with zero or negative bounds (not actually visible)
    if (bounds.width() <= 0 || bounds.height() <= 0) {
        return createEmptyNode()
    }

    val children =
        if (depth >= maxDepth || nodeCounter.count >= MAX_TOTAL_NODES) {
            emptyList()
        } else {
            (0 until childCount).mapNotNull { index ->
                getChild(index)?.toRawUiNode(
                    depth = depth + 1,
                    maxDepth = maxDepth,
                    nodeCounter = nodeCounter,
                )
            }.filter { it.visibleToUser } // Filter out empty/invisible children
        }

    return RawUiNode(
        packageName = packageName?.toString(),
        className = className?.toString(),
        viewIdResourceName = viewIdResourceName,
        text = text?.toString(),
        contentDescription = contentDescription?.toString(),
        boundsInScreen = bounds.toUiBounds(),
        actions = actionList.mapNotNull { action -> action.id.toUiNodeAction() }.distinct(),
        enabled = isEnabled,
        clickable = isClickable,
        editable = isEditable,
        focused = isFocused,
        selected = isSelected,
        checkable = isCheckable,
        checked = isChecked,
        visibleToUser = isVisibleToUser,
        password = isPassword,
        children = children,
    )
}

/**
 * Creates an empty node marker for pruned branches.
 */
private fun AccessibilityNodeInfo.createEmptyNode(): RawUiNode =
    RawUiNode(
        packageName = null,
        className = null,
        viewIdResourceName = null,
        text = null,
        contentDescription = null,
        boundsInScreen = UiBounds(0, 0, 0, 0),
        actions = emptyList(),
        enabled = false,
        clickable = false,
        editable = false,
        focused = false,
        selected = false,
        checkable = false,
        checked = false,
        visibleToUser = false,
        password = false,
        children = emptyList(),
    )

private fun Rect.toUiBounds(): UiBounds =
    UiBounds(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )

private fun Int.toUiNodeAction(): UiNodeAction? =
    when (this) {
        AccessibilityNodeInfo.ACTION_CLICK -> UiNodeAction.CLICK
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> UiNodeAction.LONG_CLICK
        AccessibilityNodeInfo.ACTION_SET_TEXT -> UiNodeAction.SET_TEXT
        AccessibilityNodeInfo.ACTION_FOCUS -> UiNodeAction.FOCUS
        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> UiNodeAction.CLEAR_FOCUS
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> UiNodeAction.SCROLL_FORWARD
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> UiNodeAction.SCROLL_BACKWARD
        AccessibilityNodeInfo.ACTION_EXPAND -> UiNodeAction.EXPAND
        AccessibilityNodeInfo.ACTION_COLLAPSE -> UiNodeAction.COLLAPSE
        else -> null
    }

/**
 * Optimized maximum tree depth for UI traversal.
 * Reduced from 30 to 18 to balance detail with performance.
 */
private const val DEFAULT_MAX_NODE_DEPTH = 18

/**
 * Maximum total nodes to process in a single snapshot.
 * Prevents excessive memory usage and processing time.
 */
private const val MAX_TOTAL_NODES = 1000
