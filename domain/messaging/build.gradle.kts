plugins {
    alias(libs.plugins.android.library)
    
}

android {
    namespace = "dev.vivian.mobilebc.domain.messaging"
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
    api(project(":core:protocol"))
    implementation(project(":domain:sync"))
    implementation(project(":domain:bc-text"))
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
