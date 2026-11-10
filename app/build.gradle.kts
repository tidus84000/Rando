plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "fr.tidus.rando"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.tidus.rando"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.2.$runNumber"
    }

    // La clé de signature n'est jamais dans le dépôt : le workflow la recrée depuis le secret
    // RANDO_KEYSTORE_B64. Sans ce secret, on signe avec la clé de debug.
    val keystore = file("rando.keystore")
    signingConfigs {
        if (keystore.exists()) {
            create("rando") {
                storeFile = keystore
                storePassword = System.getenv("RANDO_KEYSTORE_PASSWORD") ?: "randorando"
                keyAlias = "rando"
                keyPassword = System.getenv("RANDO_KEYSTORE_PASSWORD") ?: "randorando"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystore.exists()) signingConfigs.getByName("rando")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi"
        )
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
