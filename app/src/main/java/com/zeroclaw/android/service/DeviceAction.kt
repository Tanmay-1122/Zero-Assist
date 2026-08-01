package com.zeroclaw.android.service

sealed class DeviceAction {
    data class OpenApp(val query: String) : DeviceAction()
    data class OpenPackage(val packageName: String) : DeviceAction()
    data class TapText(val text: String) : DeviceAction()
    data class SetText(val text: String) : DeviceAction()
    data class PressGlobal(val action: Int) : DeviceAction()
    data class Flashlight(val enabled: Boolean) : DeviceAction()
    data class Spotify(val action: String = "toggle") : DeviceAction()
    data class MakeCall(val contactNameOrNumber: String) : DeviceAction()
    data class SendSms(val contactNameOrNumber: String, val message: String) : DeviceAction()
    data class SetAlarm(val hour: Int, val minute: Int, val label: String) : DeviceAction()
    data class SetTimer(val seconds: Int, val label: String) : DeviceAction()
    data class SetVolume(val level: Int) : DeviceAction()
    data class SetBrightness(val level: Int) : DeviceAction()
    data class OpenUrl(val url: String) : DeviceAction()
    data class ToggleWifi(val enabled: Boolean) : DeviceAction()
    data class ToggleBluetooth(val enabled: Boolean) : DeviceAction()
    data object TakeScreenshot : DeviceAction()
    data object ReadNotifications : DeviceAction()
    data object Ignore : DeviceAction()
}
