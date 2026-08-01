package com.zeroclaw.android.service.devicecontrol

class RecoveryEngine {
    fun recover(lastAction: DeviceAction?, screen: String, failures: Int): DeviceAction {
        val s = screen.lowercase()
        if (s.contains("loading") || s.contains("progress")) return DeviceAction.Wait(2_000)
        if (failures >= 3 && lastAction is DeviceAction.ClickText) return DeviceAction.Scroll(DeviceAction.Direction.DOWN)
        if (failures >= 3 && lastAction is DeviceAction.OpenApp) return DeviceAction.Home
        return when (lastAction) {
            is DeviceAction.ClickText -> DeviceAction.Scroll(DeviceAction.Direction.DOWN)
            is DeviceAction.ClickAt -> DeviceAction.Back
            else -> DeviceAction.Wait(1_000)
        }
    }
}