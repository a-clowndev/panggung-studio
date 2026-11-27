import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.panggung.obs"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.panggung.obs"
        minSdk = 29
        targetSdk = 35
        versionCode = (project.findProperty("PANGGUNG_VERSION_CODE") as String?)?.toIntOrNull() ?: 102
        versionName = (project.findProperty("PANGGUNG_VERSION_NAME") as String?) ?: "0.1.2"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    val ksProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val keystorePath = System.getenv("KEYSTORE_PATH") ?: ksProps.getProperty("storeFile")
    val keystorePass = System.getenv("KEYSTORE_PASSWORD") ?: ksProps.getProperty("storePassword")
    val keyAliasVal = System.getenv("KEY_ALIAS") ?: ksProps.getProperty("keyAlias")
    val keyPass = System.getenv("KEY_PASSWORD") ?: ksProps.getProperty("keyPassword")
    val hasReleaseKeystore = !keystorePath.isNullOrBlank() &&
        !keystorePass.isNullOrBlank() &&
        !keyAliasVal.isNullOrBlank() &&
        !keyPass.isNullOrBlank() &&
        file(keystorePath).exists()

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePass
                keyAlias = keyAliasVal
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        aidl = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
