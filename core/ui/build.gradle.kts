plugins {
    alias(libs.plugins.android.library)

    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.vivian.mobilebc.core.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
