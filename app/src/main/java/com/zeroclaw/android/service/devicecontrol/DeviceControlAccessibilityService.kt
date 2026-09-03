package com.zeroclaw.android.service.devicecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class DeviceControlAccessibilityService : AccessibilityService(), DeviceControlServiceBridge {

    companion object {
        private const val TAG = "DeviceControlA11y"

        @Volatile private var current: DeviceControlAccessibilityService? = null
        private val _connected = MutableStateFlow(false)
        val connected = _connected.asStateFlow()
        fun instance(): DeviceControlAccessibilityService? = current
        fun isRunning(): Boolean = current != null
    }

    // ── Event-driven waiting infrastructure ──────────────────────────

    /** Monotonic counter incremented on meaningful accessibility events. */
    private val eventCounter = AtomicLong(0)

    /**
     * Pending waiters for UI changes. Keyed by a caller-defined tag.
     * Each waiter is completed when a relevant event fires.
     */
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Long>>()

    /** Last window/package that received a TYPE_WINDOW_STATE_CHANGED event. */
    @Volatile private var lastWindowPackage: String? = null

    /** Register a one-shot waiter that completes on the next meaningful event. */
    override fun onNextUiChange(tag: String): CompletableDeferred<Long> {
        val deferred = CompletableDeferred<Long>()
        waiters[tag] = deferred
        return deferred
    }

    /** Cancel a pending waiter. */
    override fun cancelWait(tag: String) {
        waiters.remove(tag)
    }

    /** Cancel all pending waiters. */
    fun cancelAllWaits() {
        waiters.clear()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        _connected.value = true
    }

    override fun onDestroy() {
        cancelAllWaits()
        if (current === this) current = null
        _connected.value = false
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        val now = eventCounter.incrementAndGet()

        // Track window/package changes for fast foreground detection
        val eventPkg = event.packageName?.toString()
        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (eventPkg != null) lastWindowPackage = eventPkg
                fireWaiters(now, "window_changed")
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (eventPkg != null && eventPkg != lastWindowPackage) lastWindowPackage = eventPkg
                fireWaiters(now, "content_changed")
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (eventPkg != null && eventPkg != lastWindowPackage) lastWindowPackage = eventPkg
                fireWaiters(now, "view_focused")
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (eventPkg != null) lastWindowPackage = eventPkg
                fireWaiters(now, "windows_changed")
            }
        }
    }

    private fun fireWaiters(eventId: Long, trigger: String) {
        val iterator = waiters.entries.iterator()
        while (iterator.hasNext()) {
            val (tag, deferred) = iterator.next()
            if (deferred.complete(eventId)) {
                Log.d(TAG, "UI waiter '$tag' completed by $trigger (event=$eventId)")
            }
            iterator.remove()
        }
    }

    override fun snapshot(): List<UiNodeSnapshot> {
        val output = mutableListOf<UiNodeSnapshot>()
        val roots = windows.orEmpty().mapNotNull { it.root }
        try {
            roots.forEach { traverse(it, output, 0) }
        } finally {
            roots.forEach { it.recycle() }
        }
        return output
    }

    /**
     * Fast fingerprint-only snapshot: walks the accessibility tree just enough
     * to compute a [ScreenFingerprint] without allocating full node metadata.
     *
     * The walk replicates [ScreenFingerprint.compute] field-for-field — same
     * visibility filter, same per-node hash formula, same counters — so its
     * output is directly comparable with fingerprints derived from a full
     * [snapshot]. Roughly 3× faster and allocation-light.
     */
    override fun snapshotFingerprint(): ScreenFingerprint {
        val pkg = currentPackage()
        val acc = FingerprintAccum()
        val roots = windows.orEmpty().mapNotNull { it.root }
        try {
            roots.forEach { root -> traverseForFingerprint(root, 0, acc) }
        } finally {
            roots.forEach { it.recycle() }
        }
        return ScreenFingerprint(
            contentHash = acc.hashAccum,
            packageName = pkg,
            actionableNodeCount = acc.actionable,
            hasEditableField = acc.hasEditable,
            uniqueLabelCount = acc.uniqueLabels,
        )
    }

    /** Mutable accumulator for the fingerprint tree walk. Kept as an explicit param so the recursive traversal can update the caller's counters. */
    private class FingerprintAccum {
        var hashAccum: Int = 0
        var actionable: Int = 0
        var hasEditable: Boolean = false
        var uniqueLabels: Int = 0
        val seenLabels: HashSet<String> = HashSet()
    }

    private fun traverseForFingerprint(node: AccessibilityNodeInfo, depth: Int, acc: FingerprintAccum) {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (node.isVisibleToUser && !bounds.isEmpty) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.isNotBlank() || desc.isNotBlank() ||
                node.isClickable || node.isEditable || node.isScrollable
            ) {
                val label = text.ifBlank { desc }
                val className = node.className?.toString()?.substringAfterLast('.').orEmpty()
                val clickable = node.isClickable
                val editable = node.isEditable
                val scrollable = node.isScrollable

                var h = className.hashCode()
                h = 31 * h + label.hashCode()
                h = 31 * h + if (clickable) 1 else 0
                h = 31 * h + if (editable) 1 else 0
                h = 31 * h + if (scrollable) 1 else 0
                h = 31 * h + bounds.top * 31 + bounds.left
                acc.hashAccum += h

                if (clickable || editable || scrollable) acc.actionable++
                if (editable && !acc.hasEditable) acc.hasEditable = true
                if (label.isNotBlank() && acc.seenLabels.add(label)) acc.uniqueLabels++
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                try { traverseForFingerprint(child, depth + 1, acc) } finally { child.recycle() }
            }
        }
    }

    private fun traverse(node: AccessibilityNodeInfo, out: MutableList<UiNodeSnapshot>, depth: Int) {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (node.isVisibleToUser && !bounds.isEmpty) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable) {
                val actions = mutableSetOf<Int>()
                if (node.isClickable) actions.add(16)
                if (node.isLongClickable) actions.add(32)
                if (node.isFocusable) actions.add(64)
                if (node.isScrollable) {
                    actions.add(16384) // ACTION_SCROLL_FORWARD
                    actions.add(32768) // ACTION_SCROLL_BACKWARD
                }
                if (node.isEditable) actions.add(2097152) // ACTION_SET_TEXT

                out += UiNodeSnapshot(
                    index = out.size,
                    text = text,
                    contentDescription = desc,
                    className = node.className?.toString()?.substringAfterLast('.').orEmpty(),
                    viewId = node.viewIdResourceName.orEmpty(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    checkable = node.isCheckable,
                    checked = node.isChecked,
                    focused = node.isFocused,
                    focusable = node.isFocusable,
                    enabled = node.isEnabled,
                    bounds = bounds,
                    depth = depth,
                    supportedActions = actions,
                )
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                try { traverse(child, out, depth + 1) } finally { child.recycle() }
            }
        }
    }

    override fun clickText(target: String, nodes: List<UiNodeSnapshot>?): Boolean {
        val success = withRoots { root ->
            findBestTextNode(root, target)?.let { node ->
                try { clickNodeOrAncestor(node) } finally { node.recycle() }
            } ?: false
        }
        if (success) return true
        val candidateList = nodes ?: snapshot()
        val matchingNode = candidateList.firstOrNull {
            it.text.contains(target, true) || it.contentDescription.contains(target, true)
        } ?: return false
        return clickAt(matchingNode.centerX.toFloat(), matchingNode.centerY.toFloat())
    }

    override fun clickIndex(index: Int, nodes: List<UiNodeSnapshot>?): Boolean {
        val candidateList = nodes ?: snapshot()
        val item = candidateList.getOrNull(index) ?: return false
        return clickAt(item.centerX.toFloat(), item.centerY.toFloat())
    }

    private fun findBestTextNode(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val candidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        collectTextMatches(root, target, candidates)
        return candidates.maxByOrNull { it.first }?.second.also { winner ->
            candidates.forEach { (_, node) -> if (node !== winner) node.recycle() }
        }
    }

    private fun collectTextMatches(
        node: AccessibilityNodeInfo,
        target: String,
        out: MutableList<Pair<Int, AccessibilityNodeInfo>>
    ) {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val exact = text.equals(target, true) || desc.equals(target, true)
        val contains = text.contains(target, true) || desc.contains(target, true)
        if (exact || contains) {
            var score = if (exact) 100 else 50
            if (node.isClickable) score += 30
            if (!node.isEditable) score += 10
            out += score to AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                try { collectTextMatches(child, target, out) } finally { child.recycle() }
            }
        }
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var cursor: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        try {
            while (cursor != null) {
                if (cursor.isClickable && cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                val parent = cursor.parent
                cursor.recycle()
                cursor = parent
            }
        } finally {
            cursor?.recycle()
        }
        val r = Rect().also(node::getBoundsInScreen)
        return !r.isEmpty && clickAt(r.centerX().toFloat(), r.centerY().toFloat())
    }

    override fun typeText(text: String, hint: String?): Boolean = withRoots { root ->
        val field = findEditable(root, hint) ?: return@withRoots false
        try {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val bounds = Rect().also(field::getBoundsInScreen)
            if (!bounds.isEmpty) {
                clickAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally { field.recycle() }
    }

    private fun findEditable(node: AccessibilityNodeInfo, hint: String?): AccessibilityNodeInfo? {
        if (node.isEditable) {
            val haystack = listOf(node.text, node.contentDescription, node.hintText)
                .joinToString(" ") { it?.toString().orEmpty() }
            if (hint.isNullOrBlank() || haystack.contains(hint, true)) return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = try { findEditable(child, hint) } finally { child.recycle() }
                if (found != null) return found
            }
        }
        return null
    }

    override fun pressEnter(): Boolean {
        val pressed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            withRoots { root ->
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@withRoots false
                try { focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }
                finally { focused.recycle() }
            }
        } else false
        if (pressed) return true
        val displayMetrics = resources.displayMetrics
        val enterX = displayMetrics.widthPixels * 0.9f
        val enterY = displayMetrics.heightPixels * 0.95f
        return clickAt(enterX, enterY)
    }

    override fun scroll(direction: DeviceAction.Direction): Boolean = withRoots { root ->
        val node = findScrollable(root) ?: return@withRoots false
        try {
            node.performAction(
                if (direction == DeviceAction.Direction.DOWN)
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            )
        } finally { node.recycle() }
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = try { findScrollable(child) } finally { child.recycle() }
                if (found != null) return found
            }
        }
        return null
    }

    override fun clickAt(x: Float, y: Float): Boolean = gesture(x, y, x, y, 100)
    override fun swipe(sx: Float, sy: Float, ex: Float, ey: Float, duration: Long): Boolean =
        gesture(sx, sy, ex, ey, duration)

    private fun gesture(sx: Float, sy: Float, ex: Float, ey: Float, duration: Long): Boolean {
        val path = Path().apply { moveTo(sx, sy); if (sx != ex || sy != ey) lineTo(ex, ey) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(g, null, null)
    }

    override fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    override fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    override fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    override fun notifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    override fun currentPackage(): String? {
        // Fast path: use the last window-changed event which fires most frequently
        // and is already updated in onAccessibilityEvent.
        val fastPkg = lastWindowPackage
        if (fastPkg != null) return fastPkg

        // Slow path: walk the window tree — sort by (isActive, isFocused) properly.
        return windows.orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.isActive }
                    .thenByDescending { it.isFocused }
            )
            .firstNotNullOfOrNull { window ->
                window.root?.let { root -> try { root.packageName?.toString() } finally { root.recycle() } }
            }
    }

    /**
     * Wait for a meaningful UI change event, with a bounded timeout.
     *
     * Returns immediately if the fingerprint has already changed since
     * [preActionFingerprint] was captured, or when an accessibility event
     * fires. Falls back to the timeout if no event arrives.
     *
     * @param tag Caller-defined tag for cancellation.
     * @param timeoutMs Maximum wait time in milliseconds.
     * @param preActionFingerprint The screen fingerprint captured before the action.
     * @return True if a UI change was detected (event or fingerprint change), false on timeout.
     */
    override suspend fun waitForUiChange(
        tag: String,
        timeoutMs: Long,
        preActionFingerprint: ScreenFingerprint,
    ): Boolean {
        // Register waiter FIRST to avoid missing events that fire between
        // the snapshot check and the await call.
        val waiter = onNextUiChange(tag)
        try {
            // Fast path: check if screen already changed *after* registering
            // the waiter so we don't miss events in the race window.
            val currentFp = ScreenFingerprint.compute(snapshot(), currentPackage())
            if (currentFp.hasChanged(preActionFingerprint)) return true

            // Wait for event with timeout — no extra snapshot needed on timeout
            // because the caller (postActionVerify) always takes its own snapshot.
            return try {
                kotlinx.coroutines.withTimeout(timeoutMs) {
                    waiter.await()
                    true
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                false
            }
        } finally {
            cancelWait(tag)
        }
    }

    private inline fun withRoots(block: (AccessibilityNodeInfo) -> Boolean): Boolean {
        for (window in windows.orEmpty()) {
            val root = window.root ?: continue
            try { if (block(root)) return true } finally { root.recycle() }
        }
        return false
    }
}
