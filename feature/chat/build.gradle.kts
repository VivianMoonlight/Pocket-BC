plugins {
    alias(libs.plugins.android.library)

    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.vivian.mobilebc.feature.chat"
    compileSdk = 36

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":domain:session-core"))
    implementation(project(":domain:session"))
    implementation(project(":domain:messaging"))
    implementation(project(":domain:character"))
    implementation(project(":core:ui"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
