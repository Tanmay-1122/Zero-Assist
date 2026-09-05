package com.zeroclaw.android.service.needle

/**
 * Process-wide kill switches for Needle integration. Defaults OFF.
 *
 * [plannerEnabled] gates [com.zeroclaw.android.service.devicecontrol.NeedleFirstPlanner]
 * in `DeviceControlCallbackHandler`: OFF preserves today's cloud-only behavior
 * byte-for-byte. Follow-up: persist to DataStore and expose a settings toggle;
 * until then this is flipped only by debug builds/tests.
 */
object NeedleFlags {

    const val plannerEnabled: Boolean = true
}
