plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ryucortex.onehourlock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ryucortex.onehourlock"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // ロック中の QR コード読み取り
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
