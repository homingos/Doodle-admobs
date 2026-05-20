import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load release signing config from keystore.properties (gitignored). The
// keystore itself (release.jks) is also gitignored — back it up offline,
// losing it means you can never publish an update to Play Store.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.doodlepop.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.doodlepop.app"
        minSdk = 26 // 26+ avoids needing legacy PNG launcher icons (adaptive icons are API 26+)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Universal Google test IDs — safe to use on your own device.
            buildConfigField(
                "String",
                "ADMOB_INTERSTITIAL_UNIT_ID",
                "\"ca-app-pub-3940256099942544/1033173712\""
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real interstitial unit ID from AdMob console.
            buildConfigField(
                "String",
                "ADMOB_INTERSTITIAL_UNIT_ID",
                "\"ca-app-pub-7343868790309663/2507587335\""
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Google Mobile Ads (AdMob)
    implementation("com.google.android.gms:play-services-ads:23.3.0")
    // User Messaging Platform — required for GDPR/CCPA consent before showing ads.
    implementation("com.google.android.ump:user-messaging-platform:2.2.0")
}
