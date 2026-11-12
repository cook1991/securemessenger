import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing credentials live in keystore.properties (git-ignored).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

// Real relay coordinates (IP/port/cert pin/cert PEM) live in relay-config.properties
// (git-ignored), not in RelayConfig.kt -- this repo is public, so a hardcoded IP/cert
// in committed source would be readable by anyone browsing it, defeating the point of
// using a self-signed cert over a connect-by-IP address instead of a public CA. Only
// the compiled app needs the real values; the source stays generic.
val relayConfigFile = rootProject.file("relay-config.properties")
val relayConfigProperties = Properties().apply {
    if (relayConfigFile.exists()) load(FileInputStream(relayConfigFile))
}

android {
    namespace = "net.spritzlive.securemessenger"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.spritzlive.securemessenger"
        // 26, not smsmmsapp's 24: AndroidKeystore's hardware-backed AES-GCM support is
        // materially more consistent from API 26 onward, and this app's entire security
        // model (per-message crypto-shredding, identity key wrapping) leans on it.
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5"
        vectorDrawables { useSupportLibrary = true }
        // arm64-v8a only: every real phone this app targets (minSdk 26 already rules
        // out anything old enough to plausibly be 32-bit-only) is arm64, and libsignal's
        // native lib is tens of MB per ABI — x86/x86_64 (emulator-only) and armeabi-v7a
        // would roughly double a self-hosted direct download for no device this app
        // will ever run on.
        ndk {
            abiFilters += setOf("arm64-v8a")
        }

        buildConfigField("String", "RELAY_HOST", "\"${relayConfigProperties.getProperty("relay.host", "")}\"")
        buildConfigField("int", "RELAY_PORT", relayConfigProperties.getProperty("relay.port", "8443"))
        buildConfigField(
            "String", "RELAY_PIN_SHA256_B64",
            "\"${relayConfigProperties.getProperty("relay.pinSha256Base64", "")}\""
        )
        // The properties loader already turned the file's literal \n sequences into real
        // newlines; re-escape them back to \n *text* here so the generated BuildConfig.java
        // is valid source -- javac's own string-literal parsing turns them back into real
        // newlines at compile time, so RelayConfig.kt sees an ordinary multi-line PEM string.
        buildConfigField(
            "String", "RELAY_CERT_PEM",
            "\"${relayConfigProperties.getProperty("relay.certPem", "").replace("\n", "\\n")}\""
        )
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by libsignal-android (uses java.time / other APIs needing desugaring
        // on minSdk < 26... already 26 here, but the library itself declares the
        // requirement regardless, so Gradle enforces it at the AAR-metadata level).
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // libsignal-android's AAR embeds its own JVM-test-harness natives (desktop
            // .dylib/.dll, plus a *_testing.so per Android ABI) alongside the real
            // runtime libs — none of it is ever loaded by this app, and together with
            // the ABI filter above it's most of why an unfiltered build balloons past
            // 600MB. Dropped here rather than relying on shrinking, since these aren't
            // classes minify can reach.
            excludes += "**/*.dylib"
            excludes += "**/*.dll"
        }
        jniLibs {
            excludes += "**/libsignal_jni_testing.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // Signal's own open-source, audited Double Ratchet + X3DH/PQXDH implementation.
    // AGPLv3-licensed — fine for this personal, non-redistributed use; worth revisiting
    // if the app is ever distributed or its source published.
    implementation(libs.libsignal.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Relay transport: WebSocket client + CertificatePinner for the self-signed relay cert.
    implementation(libs.okhttp)

    // Ed25519 for the relay-auth key: platform java.security "Ed25519" support turned out
    // to be inconsistent across real devices (works on Android 14, NoSuchAlgorithmException
    // on Android 11 — confirmed on real hardware, not a guess), so this uses BouncyCastle's
    // own implementation instead of gambling on whichever Conscrypt version an OS ships.
    implementation(libs.bouncycastle)

    // QR pairing: generate (zxing core) and scan (zxing-android-embedded, which also
    // depends on zxing core itself, but the explicit version pin stays consistent
    // with this project's version-catalog convention).
    implementation(libs.zxing.android.embedded)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
