plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.vivian.mobilebc.domain.bctext"
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
    implementation(libs.json)

    testImplementation(libs.junit)
}
