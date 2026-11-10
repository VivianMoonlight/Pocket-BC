plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.vivian.mobilebc.domain.session"
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
    implementation(project(":domain:messaging"))
    api(project(":domain:session-core"))
    implementation(project(":domain:character"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
