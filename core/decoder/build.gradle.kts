plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.henry.encodec.decoder"
    compileSdk = 35

    defaultConfig { minSdk = 26 }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:ecdc"))
    implementation("org.pytorch:executorch-android:1.0.0")
}
