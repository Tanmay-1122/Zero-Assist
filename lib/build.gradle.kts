import java.io.File

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.atomicfu)
    alias(libs.plugins.gobley.cargo)
    alias(libs.plugins.gobley.uniffi)
    `maven-publish`
}

android {
    namespace = "com.zeroclaw.lib"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    // Fix for Gobley plugin NPE: Gobley 0.3.7 directly accesses the deprecated ndkDirectory
    // which can be null in AGP 8.x. We explicitly set the NDK path here.
    // In AGP 8.x, we use ndkPath (String) instead of the read-only ndkDirectory.
    ndkPath = sdkDirectory.resolve("ndk/$ndkVersion").absolutePath

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86"))
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

cargo {
    packageDirectory = project.file("../zeroclaw-android/zeroclaw-ffi")
}

uniffi {
    generateFromLibrary {
        packageName = "com.zeroclaw.ffi"
    }
}

dependencies {
    api(libs.jna) {
        artifact {
            type = "aar"
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.zeroclaw"
            artifactId = "zeroclaw-android"
            version = "0.0.37"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
    repositories {
        maven {
            name = "LocalRepository"
            url = uri(layout.buildDirectory.dir("repo"))
        }
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Natfii/ZeroClaw-Android")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
