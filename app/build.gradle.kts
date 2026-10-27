import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val envFile = rootProject.file(".env")
val envProperties = Properties().apply {
    if (envFile.exists()) {
        envFile.bufferedReader().use { load(it) }
    }
}

fun getSecret(key: String, fallback: String = ""): String =
    System.getenv(key) ?: envProperties.getProperty(key, fallback)

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.animarvle.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.animarvle.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.1.4"

        val tmdbKey = getSecret("TMDB_API_KEY")
        val googleClientId = getSecret("GOOGLE_WEB_CLIENT_ID")
        val malClientId = getSecret("MAL_CLIENT_ID")
        val malClientSecret = getSecret("MAL_CLIENT_SECRET")
        val simklClientId = getSecret("SIMKL_CLIENT_ID")

        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbKey\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleClientId\"")
        buildConfigField("String", "MAL_CLIENT_ID", "\"$malClientId\"")
        buildConfigField("String", "MAL_CLIENT_SECRET", "\"$malClientSecret\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"$simklClientId\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            val releaseConfig = signingConfigs.getByName("release")
            signingConfig = if (releaseConfig.storeFile != null && releaseConfig.storeFile!!.exists()) {
                releaseConfig
            } else {
                null
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    implementation(project(":source-api"))
    implementation(project(":domain"))
    implementation(project(":source-sample"))

    // Vendored Aniyomi source-api + core/common (Apache-2.0) — enables loading real Aniyomi
    // extension APKs the user installs. See docs/DECISIONS.md "Vendoring the Aniyomi source-api".
    implementation(project(":aniyomi-api"))

    implementation(libs.play.services.auth)

    testImplementation(libs.junit)
}
