plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kz.jarvis.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "kz.jarvis.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "2.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src")
            res.srcDirs("res")
            manifest.srcFile("AndroidManifest.xml")
            assets.srcDirs("assets")
        }
    }
}

dependencies {
    // Проект сознательно без внешних зависимостей: только Android SDK + Kotlin stdlib.
}
