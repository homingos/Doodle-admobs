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
    compileSdk = 36

    defaultConfig {
        applicationId = "com.doodlepop.app"
        minSdk = 26 // 26+ avoids needing legacy PNG launcher icons (adaptive icons are API 26+)
        targetSdk = 36
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
            // Interstitial: use the PRODUCTION unit ID so AdMob's mediation
            // group (TestMediationVertexAndroid) is invoked and the Vertex
            // custom event has a chance to win the waterfall. Pair this with
            // a high custom-event eCPM in the AdMob console so Vertex always
            // beats AdMob Network bidding — that guarantees the only ad ever
            // shown on a debug device is our own Vertex creative, which is
            // safe to tap. NEVER click an ad in a debug build that turns out
            // NOT to be Vertex (a Google fallback ad means the waterfall fell
            // through — log the impression for diagnosis but don't tap it,
            // or AdMob will flag the click as invalid traffic).
            //
            // Banner stays on the universal Google test ID — banner has no
            // mediation custom event, so testing it against the real unit
            // would risk invalid clicks.
            buildConfigField(
                "String",
                "ADMOB_INTERSTITIAL_UNIT_ID",
                "\"ca-app-pub-7343868790309663/2507587335\""
            )
            buildConfigField(
                "String",
                "ADMOB_BANNER_UNIT_ID",
                "\"ca-app-pub-3940256099942544/6300978111\""
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real ad unit IDs from AdMob console.
            buildConfigField(
                "String",
                "ADMOB_INTERSTITIAL_UNIT_ID",
                "\"ca-app-pub-7343868790309663/2507587335\""
            )
            buildConfigField(
                "String",
                "ADMOB_BANNER_UNIT_ID",
                "\"ca-app-pub-7343868790309663/1741364217\""
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
