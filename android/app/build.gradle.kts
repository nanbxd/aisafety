plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}
android {
    buildFeatures { buildConfig = true }
    namespace = "kz.safetyhack.audiotester"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        val rtcOrigin = providers.gradleProperty("RTC_BASE_URL").orElse(providers.environmentVariable("RTC_BASE_URL")).orElse("https://safetyhack-webrtc.onrender.com").get().trim()
        require(rtcOrigin.isEmpty() || Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?").matches(rtcOrigin)) { "RTC_BASE_URL must be an HTTPS origin without a path" }
        buildConfigField("String", "RTC_BASE_URL", "\"$rtcOrigin\"")
        applicationId = "kz.safetyhack.audiotester"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.7-skepticall"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.google.firebase:firebase-messaging:24.1.2")
    implementation("io.github.webrtc-sdk:android:125.6422.07")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
