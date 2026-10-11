plugins {
    id("com.android.application")
}

android {
    namespace = "dev.khoichon.guidinglight"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.khoichon.guidinglight"
        minSdk = 26 // TYPE_APPLICATION_OVERLAY
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Sounds are opened with openFd(), which needs them stored uncompressed.
    androidResources {
        noCompress += listOf("mp3", "wav", "ttf", "png")
    }
}
// No dependencies on purpose: everything is plain framework API.
