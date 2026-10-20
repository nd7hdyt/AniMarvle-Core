import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "eu.kanade.tachiyomi.animesource"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Dependencies of the vendored Aniyomi source-api + core/common (see their build.gradle.kts
    // at v0.18.2.1). Compose runtime is deliberately NOT pulled in: `@Stable` is shimmed.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.json.okio)
    api(libs.okhttp)
    api(libs.okhttp.logging)
    api(libs.okhttp.dnsoverhttps)
    api(libs.okio)
    api(libs.jsoup)
    api(libs.rxjava1)
    api(libs.nanohttpd)
    api(libs.androidx.preference.ktx)
    api(libs.injekt)
    api(libs.logcat)
    api(libs.quickjs.android)
    api(libs.torrserver)

    implementation(libs.androidx.core.ktx)
}