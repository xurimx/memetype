import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing: keystore.properties (git-ignored; see keystore.properties.example) next to
// this project. When it is absent the release build type stays unsigned and debug builds work.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

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
        // Google Play: new apps and updates must target API 36 from 31 Aug 2026.
        targetSdk {
            version = release(36)
        }
        versionCode = 4
        versionName = "0.4"
    }

    // Two distributions of the same app:
    //  foss — GitHub / F-Droid APK: bundled "Classic memes" pack, Donate link.
    //  play — Google Play: no bundled meme images (IP policy), no Donate link (Payments policy);
    //         templates come from the online sources.
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "DONATE_ENABLED", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "DONATE_ENABLED", "false")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
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
