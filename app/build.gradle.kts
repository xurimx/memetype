plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.umo.memetype"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.umo.memetype"
        minSdk {
            version = release(28)
        }
        targetSdk {
            version = release(35)
        }
        versionCode = 2
        versionName = "0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Kotlin is compiled by AGP's built-in support (AGP 9+); jvmTarget follows targetCompatibility.
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
}
