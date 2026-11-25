plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nightstand"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nightstand"
        // 30: WindowInsetsController for immersive mode. Nothing older is worth
        // supporting for a personal sideloaded app aimed at a 2026 phone.
        minSdk = 30
        // 35 (Android 15). Everything this app relies on — specialUse
        // foreground service started from BOOT_COMPLETED, full-screen intent,
        // overlay-permission background launch — is allowed at 35.
        targetSdk = 35
        versionCode = 5
        versionName = "0.5"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Compose for the settings screens only; the nightstand display stays Canvas.
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" } // matches Kotlin 1.9.24
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    testImplementation("junit:junit:4.13.2")
    // Renders pages to PNG on the JVM (PagePreviewTest) — layout checks without a device.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Robolectric's own downloader doesn't go through HTTP proxies reliably; let
// Gradle fetch the Android runtime jar and run Robolectric offline against it.
val robolectricRuntime: Configuration by configurations.creating
dependencies {
    robolectricRuntime("org.robolectric:android-all-instrumented:14-robolectric-10818077-i6")
}
val robolectricJars = layout.buildDirectory.dir("robolectric-jars")
val copyRobolectricJars by tasks.registering(Copy::class) {
    from(robolectricRuntime)
    into(robolectricJars)
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJars.get().asFile.absolutePath)
}
