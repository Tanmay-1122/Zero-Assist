package com.zeroclaw.android.service.needle

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier B smoke test for the Needle 2 native engine (READ-ONLY gate: §6 stays
 * OFF until this passes on hardware).
 *
 * PHYSICAL-DEVICE-ONLY: `libneedle_jni.so` ships a real engine for
 * arm64-v8a only (AAR-equivalent finding: `jni/arm64-v8a` alone; x86_64
 * emulator builds carry a failing-fast stub). The test self-skips on any
 * other ABI via [Assume], so CI's x86_64 managed device (`pixel7Api35`)
 * reports SKIPPED, never a false failure.
 *
 * Round-trip asserted: asset → stage → load → init → `complete("open
 * Instagram")` returns a non-empty `function_calls` list whose first call is
 * `open_app`, within the 4s planner cap.
 *
 * Cannot run headless here; execute with an arm64 device attached:
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...NeedleSmokeTest`
 */
@RunWith(AndroidJUnit4::class)
class NeedleSmokeTest {

    private lateinit var engine: NeedleEngine
    private lateinit var modelManager: NeedleModelManager

    @Before
    fun assumeArm64() {
        Assume.assumeTrue(
            "Needle engine requires arm64-v8a; skipping on ${Build.SUPPORTED_ABIS.toList()}",
            Build.SUPPORTED_ABIS.contains("arm64-v8a"),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        engine = NeedleEngine(context)
        modelManager = NeedleModelManager(context)
    }

    @Test(timeout = 120_000)
    fun openInstagramRoundTrip() = runBlocking {
        val modelFile = withTimeout(60_000) { modelManager.ensureModel() }
        Assume.assumeTrue(modelFile.length() >= NeedleModelManager.MIN_MODEL_BYTES)

        withTimeout(60_000) {
            Assume.assumeTrue("native load failed", engine.load(modelFile))
            // Measurement window for E (dumpsys meminfo before/after load).
            Thread.sleep(2_000)
            Assume.assumeTrue(
                "native init failed",
                engine.initialize(
                    NeedlePromptCompressor.SYSTEM_HINT,
                    NeedleToolSchema.toolsJson,
                ),
            )
        }

        val startMs = System.currentTimeMillis()
        val raw = withTimeout(NEEDLE_STEP_TIMEOUT_MS) {
            engine.complete("open Instagram")
        }
        Log.d(TAG, "needle_latency_ms=${System.currentTimeMillis() - startMs}")
        assertNotNull("empty engine response", raw)
        Log.d(TAG, "needle_raw=$raw")

        val root = JSONObject(raw!!)
        val calls = root.getJSONArray("function_calls")
        Assume.assumeTrue("empty toolCalls", calls.length() > 0)
        val first = calls.getJSONObject(0)
        Log.d(TAG, "needle_tool0_name=${first.getString("name")}")
        Log.d(TAG, "needle_tool0_args=${first.getJSONObject("arguments")}")
        Log.d(TAG, "needle_confidence=${root.optDouble("confidence", Double.NaN)}")
        assertEquals("open_app", first.getString("name"))
    }

    companion object {
        private const val TAG = "NeedleSmokeTest"
        private const val NEEDLE_STEP_TIMEOUT_MS = 4_000L
    }
}
