plugins {
    alias(libs.plugins.android.library)

}

android {
    namespace = "dev.vivian.mobilebc.core.model"
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
    testImplementation(libs.junit)
}
