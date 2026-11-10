plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.vivian.mobilebc.domain.session.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
