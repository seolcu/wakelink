import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// Release signing is optional at the Gradle-config level: CI and other
// machines without keystore/wakelink-upload.properties (gitignored --
// never commit a signing key) can still run assembleDebug/lintDebug; only
// assembleRelease needs it, and fails with a clear "not found" only when
// actually invoked without the keystore present.
val keystorePropertiesFile = rootProject.file("keystore/wakelink-upload.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "app.wakelink.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.wakelink.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Overridable at build time for self-hosted forks; the official
        // Play Store build ships with the maintainer's default instance.
        // See /docs/self-hosting.md -- this alone does NOT make an
        // official-build install work against your own server, because
        // FCM tokens are scoped to the Firebase project baked into
        // google-services.json.
        buildConfigField("String", "DEFAULT_SERVER_BASE_URL", "\"https://wake.seolcu.com\"")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file("keystore/${keystoreProperties["storeFile"]}")
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging.ktx)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp.logging.interceptor)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
}
