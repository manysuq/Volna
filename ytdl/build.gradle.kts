plugins {
    id("com.android.library")
}

android {
    namespace = "com.ytdl.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Наша библиотека написана на Java 8 и не должна тянуть Android-зависимости
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
