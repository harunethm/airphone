plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing reads AIRPHONE_RELEASE_* from ~/.gradle/gradle.properties (never committed), so the
// keystore and its passwords never touch this repo. Without them the release build is unsigned and
// release.sh refuses to publish it.
val (releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword) =
    listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
        .map { providers.gradleProperty("AIRPHONE_RELEASE_$it").orNull }
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.scylla.airphone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.scylla.airphone"
        minSdk = 28 // BluetoothHidDevice API
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!.replaceFirst("~", System.getProperty("user.home")))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.1")
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    testImplementation("junit:junit:4.13.2")
}
