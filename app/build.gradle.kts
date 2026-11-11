plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.samblakeman.omarchylauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.samblakeman.omarchylauncher"
        minSdk = 33
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
    }

    // The release key is read from ~/.gradle/gradle.properties (OMA_STORE_FILE and friends),
    // never from the repo. Debug builds use it too when present, so a debug build can update
    // a release install in place. Without it, debug builds fall back to the usual debug key.
    val releaseKey = providers.gradleProperty("OMA_STORE_FILE").orNull
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey)
            storePassword = providers.gradleProperty("OMA_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("OMA_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("OMA_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
        }
        getByName("debug") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
}
