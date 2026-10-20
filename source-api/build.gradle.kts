plugins {
    id("com.android.library")
}

android {
    namespace = "com.animarvle.sourceapi"
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
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ARCH §5: this is the contract module published for plugin authors. It carries
// no dependencies on purpose - a plugin should not inherit our libraries.
dependencies {
    implementation(libs.kotlinx.coroutines.core)
}