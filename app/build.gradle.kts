plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mazze.sportsclaude"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mazze.sportsclaude"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.1"
    }

    signingConfigs {
        getByName("debug") {
            val f = rootProject.file("debug.keystore")
            if (f.exists()) {
                storeFile = f
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
