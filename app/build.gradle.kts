import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val localConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val googleMapsKey = (providers.gradleProperty("GOOGLE_MAPS_API_KEY").orNull
    ?: System.getenv("DAILYONE_GOOGLE_MAPS_KEY") ?: localConfig.getProperty("google.maps.api.key", "")).trim()
require(googleMapsKey.isEmpty() || Regex("AIza[0-9A-Za-z_-]{35}").matches(googleMapsKey)) { "Invalid Google Maps API key configuration" }
android {
    namespace = "com.firzen.dailyone"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.firzen.dailyone"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "0.9.4"
        manifestPlaceholders["googleMapsApiKey"] = googleMapsKey
        buildConfigField("boolean", "GOOGLE_MAPS_CONFIGURED", googleMapsKey.isNotBlank().toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.google.android.gms:play-services-maps:20.1.0")
    implementation("com.amap.api:3dmap-location-search:11.3.100_loc11.3.101_sea9.8.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
