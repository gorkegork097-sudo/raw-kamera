plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.rawkamera"
    // 35: flaş gücü ayarı (Android 15) için gerekli
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rawkamera"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Sabit imza: her yeni sürüm eskisinin üzerine kurulabilsin diye.
    signingConfigs {
        create("release") {
            storeFile = file("rawkamera.keystore")
            storePassword = "rawkamera"
            keyAlias = "rawkamera"
            keyPassword = "rawkamera"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
