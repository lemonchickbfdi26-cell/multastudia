plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.leon.multastudia"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.leon.multastudia"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    // Постоянный ключ подписи: благодаря ему новые версии ставятся поверх старых
    signingConfigs {
        create("fixed") {
            storeFile = file("multastudia.jks")
            storePassword = "multastudia"
            keyAlias = "multastudia"
            keyPassword = "multastudia"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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
