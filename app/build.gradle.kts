plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "id.webadb.hmdm"
    compileSdk = 34
    defaultConfig {
        applicationId = "id.webadb.hmdm"
        minSdk = 24
        targetSdk = 34
        versionCode = 22
        versionName = "2.2"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
