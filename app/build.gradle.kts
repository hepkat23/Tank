import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}

// Release signing is read from a file outside the repo (path in TANK_SIGNING or the default
// below). Without it, release builds are simply unsigned; debug builds always work.
val signing = Properties().apply {
    val f = file(System.getenv("TANK_SIGNING") ?: "${System.getProperty("user.home")}/.local/share/tank-signing/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "workshop.readingcompanion"
    compileSdk = 35

    defaultConfig {
        applicationId = "workshop.readingcompanion"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        if (signing.containsKey("TANK_STORE_FILE")) create("release") {
            storeFile = file(signing.getProperty("TANK_STORE_FILE"))
            storePassword = signing.getProperty("TANK_STORE_PASSWORD")
            keyAlias = signing.getProperty("TANK_KEY_ALIAS")
            keyPassword = signing.getProperty("TANK_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
