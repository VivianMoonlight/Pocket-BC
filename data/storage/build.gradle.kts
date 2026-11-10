plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.vivian.mobilebc.data.storage"
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
    implementation(project(":core:protocol"))
    implementation(project(":domain:messaging"))
    implementation(project(":domain:sync"))
    implementation(project(":domain:session"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)

    ksp(libs.androidx.room.compiler)
}

tasks.register("compileKotlin") {
    dependsOn("compileDebugKotlin")
}
