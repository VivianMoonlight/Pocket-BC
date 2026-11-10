plugins {
    alias(libs.plugins.android.library)

}

android {
    namespace = "dev.vivian.mobilebc.core.protocol"
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
    api(project(":core:model"))
    api(libs.gson)
    implementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
