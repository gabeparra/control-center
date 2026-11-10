import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build-time config: repo-local, gitignored `config.properties` (see
// config.example.properties for the template). Missing file = every key
// falls back to a harmless default below, so a clean clone still builds.
val localConfig = Properties().apply {
    val f = rootProject.file("config.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, default: String) = (localConfig.getProperty(key) ?: default)

android {
    namespace = "app.controlcenter"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.controlcenter"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Shared secret gating the exported update-trigger receiver: raises the
        // bar from "any app can poke it" to "must know this token". Set
        // trigger.token in config.properties to your own value and keep your
        // bridge's trigger call in sync.
        buildConfigField("String", "TRIGGER_TOKEN",
            "\"${cfg("trigger.token", "CHANGE-ME-trigger-token")}\"")
        // Initial value for the bridge URL field, before Settings has ever been
        // saved. Blank by default; set default.bridge.url in config.properties
        // if you always point at the same box.
        buildConfigField("String", "DEFAULT_BRIDGE_URL",
            "\"${cfg("default.bridge.url", "")}\"")
    }

    signingConfigs {
        // Sign release with the SAME debug keystore so hardened release builds
        // still install over the top of existing (debug-signed) installs.
        create("relDebug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Real release signing, only if config.properties supplies a keystore.
        // Falls back to relDebug (above) when signing.storeFile is unset, so
        // the release build always works, even before you have a keystore.
        val storeFilePath = cfg("signing.storeFile", "")
        if (storeFilePath.isNotBlank()) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = cfg("signing.storePassword", "")
                keyAlias = cfg("signing.keyAlias", "")
                keyPassword = cfg("signing.keyPassword", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false           // Compose + reflection: keep R8 off
            isDebuggable = false               // no debugger attach on shipped APK
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("relDebug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
