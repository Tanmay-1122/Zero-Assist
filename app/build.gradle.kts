import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
    alias(libs.plugins.dokka)
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localProps.load(localPropsFile.inputStream())
}

val zeroAssistDebugAbis =
    if (
        providers.gradleProperty("zeroAssist.phoneOnlyAbi")
            .map { value -> value.equals("true", ignoreCase = true) }
            .orElse(false)
            .get()
    ) {
        listOf("armeabi-v7a", "arm64-v8a")
    } else {
        listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    }

android {
    namespace = "com.zeroclaw.android"
    compileSdk = 35
    ndkVersion = "25.2.9519653"

    // The app module strips packaged native libraries during assembleDebug/assembleRelease.
    // Pin it to the same known-good NDK as :lib so AGP does not fall back to a broken side-by-side install.
    ndkPath = sdkDirectory.resolve("ndk/$ndkVersion").absolutePath

    val hasReleaseSigning = localProps.getProperty("RELEASE_STORE_FILE") != null

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(localProps.getProperty("RELEASE_STORE_FILE"))
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.zeroclaw.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 39
        versionName = "0.0.37"

        // Match the app's shipped ABIs to the native library outputs from :lib.
        // armeabi-v7a is required for 32-bit ARM phones such as Galaxy M01 Core / SM-M115F.
        splits {
            abi {
                isEnable = true
                reset()
                include(*zeroAssistDebugAbis.toTypedArray())
                isUniversalApk = true
            }
        }

        buildConfigField("String", "BUILD_DATE", "\"${LocalDate.now().format(DateTimeFormatter.ofPattern("MMM yyyy"))}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // Keep code unminified for debugging and readable stack traces
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules-debug.pro",
            )
            // Keep applicationId suffix-free so the debug APK replaces the release install
            applicationIdSuffix = null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        managedDevices {
            devices {
                create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel7Api35") {
                    device = "Pixel 7"
                    apiLevel = 35
                    systemImageSource = "google"
                }
            }
            groups {
                create("ci") {
                    targetDevices.add(devices.getByName("pixel7Api35"))
                }
            }
        }
    }

    sourceSets {
        getByName("main").assets.srcDirs(
            "$projectDir/src/main/assets",
            "${rootProject.projectDir}/zeroclaw-config/assets",
        )
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    composeCompiler {
        enableStrongSkippingMode = true
        reportsDestination = layout.buildDirectory.dir("compose-reports")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/needle/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

dokka {
    moduleName.set("Zero-Assist Android")
    dokkaPublications.html {
        suppressInheritedMembers.set(true)
    }
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(projectDir.resolve("src"))
            remoteUrl("https://github.com/Tanmay-1122/Zero-Assist/tree/main/app/src")
        }
        perPackageOption {
            matchingRegex.set(".*\\.generated\\..*")
            suppress.set(true)
        }
    }
}

dependencies {
    implementation(project(":lib"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.wsc)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)
    implementation(libs.core.ktx)
    implementation(libs.security.crypto)
    implementation(libs.material)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.sqlcipher)
    implementation(libs.okhttp)
    implementation(libs.nanohttpd)
    implementation(libs.browser)
    implementation(libs.work.runtime.ktx)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mlkit.genai.summarization)
    implementation(libs.mlkit.genai.proofreading)
    implementation(libs.mlkit.genai.rewriting)
    implementation(libs.mlkit.genai.image.description)
    implementation(libs.onnxruntime.android)
    implementation(libs.litert.lm)
    implementation(libs.usb.serial.android)
    ksp(libs.room.compiler)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit5.vintage)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.room.testing)

    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20231128-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.http-client:google-http-client-gson:1.44.1") {
        exclude(group = "org.apache.httpcomponents")
    }

    // Canvas workflow: Markdown rendering
    implementation("org.commonmark:commonmark:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.21.0")
}

detekt {
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
    baseline = file("${rootProject.projectDir}/config/detekt/baseline.xml")
    buildUponDefaultConfig = true
    allRules = false
    ignoreFailures = true
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

// Android AGP does not register a bare `testClasses` task (unlike plain Java/Kotlin projects).
// Some tools (Android Studio run configs, CI scripts, third-party plugins) invoke
// `:app:testClasses` and fail with "Cannot locate tasks that match ':app:testClasses'".
// This stub registers the missing task and delegates it to the real Android equivalent so
// those callers succeed without any behaviour change.
tasks.register("testClasses") {
    group = "verification"
    description = "Stub task: compiles debug unit-test classes (AGP equivalent of testClasses)."
    dependsOn("testDebugUnitTestClasses")
}

// ── Proot native library management ──────────────────────────────────────────
// Ensures libproot.so, libproot-loader.so, libtalloc.so exist for all target ABIs
// in app/src/main/jniLibs/<abi>/. Downloads from the Termux repository if missing.
// These are required by LinuxSandboxManager at runtime.

val abiToArch = mapOf(
    "arm64-v8a" to "aarch64",
    "armeabi-v7a" to "arm",
    "x86_64" to "x86_64",
    "x86" to "i686",
)

val prootVersion = "5.1.107.84"
val tallocVersion = "2.4.3"
val shmemVersion = "0.7"

val ensureProotBinaries by tasks.registering {
    group = "build setup"
    description = "Download proot native libraries from Termux if missing in jniLibs/"

    doLast {
        val jniLibsRoot = projectDir.resolve("src/main/jniLibs")
        val targetAbis = zeroAssistDebugAbis.ifEmpty { abiToArch.keys.toList() }
        var allPresent = true

        for (abi in targetAbis) {
            val libDir = jniLibsRoot.resolve(abi)
            val required = mutableListOf("libproot.so", "libproot-loader.so", "libtalloc.so")
            if (abi in listOf("arm64-v8a", "x86_64")) required.add("libproot-loader32.so")
            val missing = required.filterNot { File(libDir, it).isFile }
            if (missing.isNotEmpty()) { allPresent = false; logger.warn("[proot] $abi: missing $missing") }
        }
        if (allPresent) { logger.info("[proot] All binaries present"); return@doLast }

        logger.warn("[proot] Downloading missing binaries from Termux repository...")
        for (abi in targetAbis) {
            val arch = abiToArch[abi] ?: continue
            val libDir = jniLibsRoot.resolve(abi)
            if (File(libDir, "libproot.so").isFile) continue
            libDir.mkdirs()
            logger.lifecycle("[proot] Acquiring binaries for $abi ($arch)")
            val dlDir = file(temporaryDir).resolve(abi).apply { mkdirs() }
            try {
                downloadDeb("proot", prootVersion, arch, dlDir)
                extractFromDeb(project, dlDir.resolve("proot.deb"), dlDir, listOf(
                    "./data/data/com.termux/files/usr/bin/proot" to "libproot.so",
                    "./data/data/com.termux/files/usr/libexec/proot/loader" to "libproot-loader.so",
                ))
                if (abi in listOf("arm64-v8a", "x86_64")) {
                    extractFromDeb(project, dlDir.resolve("proot.deb"), dlDir, listOf(
                        "./data/data/com.termux/files/usr/libexec/proot/loader32" to "libproot-loader32.so",
                    ))
                }
                downloadDeb("talloc", tallocVersion, arch, dlDir)
                extractFromDeb(project, dlDir.resolve("talloc.deb"), dlDir, listOf(
                    "./data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3" to "libtalloc.so",
                ))
                downloadDeb("shmem", shmemVersion, arch, dlDir)
                extractFromDeb(project, dlDir.resolve("shmem.deb"), dlDir, listOf(
                    "./data/data/com.termux/files/usr/lib/libandroid-shmem.so" to "libandroid-shmem.so",
                ))
                dlDir.listFiles()?.filter { it.isFile && it.name.endsWith(".so") }?.forEach { f ->
                    f.copyTo(File(libDir, f.name), overwrite = true)
                    logger.lifecycle("[proot]   Installed ${f.name}")
                }
            } catch (e: Exception) {
                throw GradleException(
                    "Failed to acquire proot binaries for $abi. " +
                    "Place built binaries in app/src/main/jniLibs/$abi/ or run build-proot.sh.", e
                )
            } finally {
                dlDir.deleteRecursively()
            }
        }
    }
}

private fun downloadDeb(pkg: String, version: String, arch: String, outDir: File) {
    val url = when (pkg) {
        "proot" -> "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_${version}_${arch}.deb"
        "talloc" -> "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_${version}_${arch}.deb"
        "shmem" -> "https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_${version}_${arch}.deb"
        else -> error("Unknown package: $pkg")
    }
    val target = outDir.resolve("$pkg.deb")
    logger.info("[proot] Downloading $url")
    URI(url).toURL().openStream().use { input: InputStream ->
        target.outputStream().use { output: OutputStream -> input.copyTo(output) }
    }
}

private fun extractFromDeb(project: Project, debFile: File, outDir: File, mappings: List<Pair<String, String>>) {
    val dataTarXz = extractArSection(debFile, "data.tar.xz")
        ?: throw GradleException("data.tar.xz not found in ${debFile.name}")
    val dataTar = outDir.resolve("data.tar")
    try {
        val xzIn = org.apache.commons.compress.compressors.xz.XZCompressorInputStream(dataTarXz.inputStream())
        dataTar.outputStream().use { out -> xzIn.copyTo(out) }
        xzIn.close()
        for ((archivePath, outputName) in mappings) {
            val shortName = archivePath.substringAfterLast("/")
            project.copy {
                from(project.tarTree(dataTar)) {
                    include("**/$shortName")
                }
                into(outDir)
                eachFile { path = outputName }
                includeEmptyDirs = false
            }
        }
    } finally {
        dataTar.delete()
    }
}

private fun extractArSection(debFile: File, sectionName: String): ByteArray? {
    val magic = "!<arch>\n"
    val data = debFile.readBytes()
    if (data.size < magic.length || data.copyOf(magic.length).decodeToString() != magic) {
        throw GradleException("Invalid ar archive: ${debFile.name}")
    }
    var offset = magic.length
    while (offset + 60 <= data.size) {
        val header = data.copyOfRange(offset, offset + 60)
        offset += 60
        val name = header.copyOfRange(0, 16).decodeToString().trimEnd().trimEnd('/')
        val sizeStr = header.copyOfRange(48, 58).decodeToString().trimEnd()
        val size = sizeStr.toLongOrNull() ?: throw GradleException("Invalid ar header in ${debFile.name}")
        val fileSize = size.toInt()
        if (offset + fileSize > data.size) throw GradleException("Truncated ar: ${debFile.name}")
        val content = data.copyOfRange(offset, offset + fileSize)
        offset += fileSize
        if (fileSize % 2 != 0) offset++
        if (name == sectionName) return content
    }
    return null
}

tasks.named("preBuild") {
    dependsOn(ensureProotBinaries)
    dependsOn(ensureNeedleLib)
}

// ── Needle 2 prebuilt engine management ─────────────────────────────────────
// Downloads android-arm64/libneedle.a from HuggingFace into
// app/src/main/cpp/needle/libs/arm64-v8a/ when missing. The .a is a build-time
// input only (gitignored); the runtime model (needle2.cact) ships as an asset.
// Other ABIs compile a JNI stub (see app/src/main/cpp/needle/CMakeLists.txt).

val needleLibVersion = "main"
val needleLibMinBytes = 10_000_000L

val ensureNeedleLib by tasks.registering {
    group = "build setup"
    description = "Download Needle 2 prebuilt engine (libneedle.a, arm64-v8a) if missing"

    doLast {
        val libFile = projectDir.resolve("src/main/cpp/needle/libs/arm64-v8a/libneedle.a")
        if (libFile.isFile && libFile.length() >= needleLibMinBytes) {
            logger.info("[needle] libneedle.a present (${libFile.length()} bytes)")
            return@doLast
        }
        libFile.parentFile.mkdirs()
        val url = "https://huggingface.co/Cactus-Compute/needle2/resolve/" +
            "$needleLibVersion/android-arm64/libneedle.a?download=true"
        logger.lifecycle("[needle] Downloading libneedle.a (~20MB) from HuggingFace...")
        try {
            URI(url).toURL().openStream().use { input: InputStream ->
                libFile.outputStream().use { output: OutputStream -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            libFile.delete()
            throw GradleException(
                "Failed to download libneedle.a. Check network access to huggingface.co " +
                    "or place android-arm64/libneedle.a at ${libFile.path} manually.", e
            )
        }
        if (libFile.length() < needleLibMinBytes) {
            libFile.delete()
            throw GradleException(
                "Downloaded libneedle.a is too small (${libFile.length()} bytes); deleted. Retry the build."
            )
        }
        logger.lifecycle("[needle] Installed libneedle.a (${libFile.length()} bytes)")
    }
}
