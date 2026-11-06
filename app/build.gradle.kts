plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.courierradar"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.courierradar"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        // Keeps the "release" APK installable without extra keystore setup.
        create("standalone") {
            storeFile = file("standalone.keystore")
            storePassword = "courierradar"
            keyAlias = "courierradar"
            keyPassword = "courierradar"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (file("standalone.keystore").exists()) {
                signingConfigs.getByName("standalone")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
