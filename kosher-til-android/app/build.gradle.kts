plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kushertil.filter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kushertil.filter"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("he", "en")

        // --- Managed-filter configuration -------------------------------------
        // Remote management server base URL (empty = not provisioned yet).
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"\"")
        // How often (minutes) the device pulls policy from the server.
        buildConfigField("int", "POLICY_SYNC_MINUTES", "15")

        // --- Development maintenance escape hatch ------------------------------
        // When MAINTENANCE_ENABLED is true, entering MAINTENANCE_CODE in the
        // hidden maintenance screen fully disables the filter and releases
        // device-admin so the app can be uninstalled. Set to false for a
        // production/Netsmart-style build where only the server can release.
        buildConfigField("boolean", "MAINTENANCE_ENABLED", "true")
        buildConfigField("String", "MAINTENANCE_CODE", "\"\\u05D0.\\u05D9.\\u05E1.\\u05D7\\u05E1\\u05D9\\u05DE\\u05D5\\u05EA\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
