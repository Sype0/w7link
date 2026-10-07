plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreFile = System.getenv("KEYSTORE_FILE")

android {
    namespace = "io.github.sype0.w7link.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sype0.w7link.phone"
        minSdk = 31
        // 34 keeps BODY_SENSORS valid on Wear OS 6 and avoids forced edge-to-edge.
        targetSdk = 34
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "dev"
    }

    // The wire protocol is shared source, compiled into both apps.
    sourceSets["main"].kotlin.srcDir("../common/src")

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a release keystore the build still installs, signed with the debug key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        checkReleaseBuilds = false
    }
}
