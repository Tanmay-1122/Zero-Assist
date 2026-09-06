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

        fun adbRun(vararg args: String): String {
            val out = java.io.ByteArrayOutputStream()
            exec {
                commandLine(listOf(adb.absolutePath) + args.toList())
                standardOutput = out
                // Don't fail yet — we produce friendlier errors below.
                isIgnoreExitValue = true
            }
            return out.toString(Charsets.UTF_8.name()).trim()
        }

        fun adbRunChecked(vararg args: String) {
            exec {
                commandLine(listOf(adb.absolutePath) + args.toList())
            }
        }

        // Fail fast with actionable guidance when no device is visible.
        val devicesOut = adbRun("devices")
        val deviceLines = devicesOut.lines().drop(1).map { it.trim() }.filter { it.isNotEmpty() }
        val readyDevices = deviceLines.filter { it.endsWith("\tdevice") }
        if (readyDevices.isEmpty()) {
            val hint = buildString {
                appendLine("No authorized ADB device found.")
                appendLine()
                appendLine("adb devices output:")
                appendLine(devicesOut.ifBlank { "<empty>" })
                appendLine()
                appendLine("Fix checklist:")
                appendLine("  1. Phone: Settings > About phone > tap Build number 7x to enable Developer options.")
                appendLine("  2. Phone: Developer options > enable USB debugging (and 'Install via USB' on MIUI/HyperOS).")
                appendLine("  3. Use a data cable (not charge-only), unlock phone, tap 'Allow' on the RSA prompt (check 'Always allow').")
                appendLine("  4. Samsung/Windows: install Samsung USB driver / Google USB driver if device shows as unknown.")
                appendLine("  5. Re-run: adb devices  (should show '<serial>\\tdevice', not 'unauthorized' or blank).")
                appendLine("  6. If multiple devices: ./gradlew installSamsungDebug -PzeroAssist.adbSerial=<serial>  (or set ANDROID_SERIAL).")
                appendLine()
                appendLine("No-install fallback (phone not detected): copy app/build/outputs/apk/debug/app-universal-debug.apk")
                appendLine("to the phone and sideload it (Files > tap APK > Allow install from this source). Pick the")
                appendLine("arm64-v8a APK for most modern phones, armeabi-v7a for 32-bit phones (e.g. Galaxy M01 Core SM-M115F).")
            }
            error(hint)
        }

        var serial =
            zeroAssistAdbSerial.orNull
                ?: System.getenv("ANDROID_SERIAL")

        if (serial.isNullOrBlank() && readyDevices.size > 1) {
            error(
                "Multiple devices found:\n" +
                    readyDevices.joinToString("\n") +
                    "\nRe-run with -PzeroAssist.adbSerial=<serial> or set ANDROID_SERIAL."
            )
        }
        if (serial.isNullOrBlank() && readyDevices.size == 1) {
            serial = readyDevices.first().substringBefore("\t").trim()
            logger.lifecycle("Using device $serial")
        }

        fun serialArgs(): List<String> =
            if (!serial.isNullOrBlank()) listOf("-s", serial!!) else emptyList()

        // Auto-select APK matching the connected device ABI; fall back to universal.
        val arm64Apk = rootProject.file("app/build/outputs/apk/debug/app-arm64-v8a-debug.apk")
        val v7Apk = rootProject.file("app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk")
        val universalApk = rootProject.file("app/build/outputs/apk/debug/app-universal-debug.apk")
        check(arm64Apk.exists() || v7Apk.exists() || universalApk.exists()) {
            "No debug APK was built in app/build/outputs/apk/debug/. Run :app:assembleDebug first."
        }

        val deviceAbi = adbRun(*((serialArgs() + listOf("shell", "getprop", "ro.product.cpu.abi")).toTypedArray()))
            .lines().firstOrNull()?.trim().orEmpty()
        logger.lifecycle("Device ABI: ${deviceAbi.ifBlank { "<unknown>" }}")

        val apk = when {
            deviceAbi.startsWith("arm64") && arm64Apk.exists() -> arm64Apk
            deviceAbi.startsWith("armeabi") && v7Apk.exists() -> v7Apk
            deviceAbi.contains("64") && arm64Apk.exists() -> arm64Apk
            universalApk.exists() -> universalApk
            arm64Apk.exists() -> arm64Apk
            else -> v7Apk
        }
        logger.lifecycle("Installing ${apk.name} to ${serial ?: "<single device>"}")

        adbRunChecked(*((serialArgs() + listOf("install", "-r", "-d", apk.absolutePath)).toTypedArray()))
        logger.lifecycle("Installed ${apk.name}. Launch the app from the launcher, or via: adb ${if (!serial.isNullOrBlank()) "-s $serial " else ""}shell am start -n com.zeroclaw.android/.MainActivity")
    }
}
