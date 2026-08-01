package com.zeroclaw.android.service.devicecontrol

import kotlinx.coroutines.CompletableDeferred

/**
 * Interface abstracting the accessibility service operations needed by the
 * device-control executor and observer.  DeviceControlAccessibilityService
 * implements this; tests can mock it without Robolectric or mockkStatic.
 */
interface DeviceControlServiceBridge {

    fun snapshot(): List<UiNodeSnapshot>
    fun currentPackage(): String?

    /**
     * Fast fingerprint-only snapshot — implementors should override this with
     * a lightweight tree walk that skips full metadata collection.
     * The default delegates to a full snapshot for backward compatibility.
     */
    fun snapshotFingerprint(): ScreenFingerprint =
        ScreenFingerprint.compute(snapshot(), currentPackage())

    fun clickText(target: String, nodes: List<UiNodeSnapshot>? = null): Boolean
    fun clickIndex(index: Int, nodes: List<UiNodeSnapshot>? = null): Boolean
    fun clickAt(x: Float, y: Float): Boolean
    fun typeText(text: String, hint: String?): Boolean
    fun pressEnter(): Boolean
    fun scroll(direction: DeviceAction.Direction): Boolean
    fun swipe(sx: Float, sy: Float, ex: Float, ey: Float, duration: Long): Boolean
    fun back(): Boolean
    fun home(): Boolean
    fun recents(): Boolean
    fun notifications(): Boolean

    fun onNextUiChange(tag: String): CompletableDeferred<Long>
    fun cancelWait(tag: String)
    suspend fun waitForUiChange(
        tag: String,
        timeoutMs: Long,
        preActionFingerprint: ScreenFingerprint,
    ): Boolean
}
