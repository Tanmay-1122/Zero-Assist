import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.atomicfu) apply false
    alias(libs.plugins.gobley.cargo) apply false
    alias(libs.plugins.gobley.uniffi) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.dokka) apply false
}

val zeroAssistAdbSerial = providers.gradleProperty("zeroAssist.adbSerial")

tasks.register("installSamsungDebug") {
    group = "install"
    description = "Builds the ARM debug APK and installs it to the connected Samsung phone via ADB."

    dependsOn(":app:assembleDebug")

    doLast {
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use(localProperties::load)
        }

        val sdkDir =
            localProperties.getProperty("sdk.dir")
                ?: System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
                ?: error("Android SDK not found. Set sdk.dir in local.properties or ANDROID_HOME.")

        val adbCandidates =
            listOf(
                rootProject.file("$sdkDir/platform-tools/adb.exe"),
                rootProject.file("$sdkDir/platform-tools/adb"),
            )
        val adb =
            adbCandidates.firstOrNull { it.exists() }
                ?: error("ADB not found under $sdkDir/platform-tools.")

        val apk = rootProject.file("app/build/outputs/apk/debug/app-arm64-v8a-debug.apk")
        check(apk.exists()) {
            "Expected APK was not built: ${apk.absolutePath}"
        }

        val serial =
            zeroAssistAdbSerial.orNull
                ?: System.getenv("ANDROID_SERIAL")

        val command =
            mutableListOf(adb.absolutePath).apply {
                if (!serial.isNullOrBlank()) {
                    add("-s")
                    add(serial)
                }
                addAll(listOf("install", "-r", "-d", apk.absolutePath))
            }

        exec {
            commandLine(command)
        }
    }
}
