plugins {
    alias(libs.plugins.android.library)
    
}

android {
    namespace = "dev.vivian.mobilebc.core.socket"
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
    implementation(project(":domain:session-core"))
    implementation(project(":domain:sync"))
    implementation(project(":domain:character"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(libs.json)
    implementation(libs.socket.io.client)

    testImplementation(libs.junit)
}
