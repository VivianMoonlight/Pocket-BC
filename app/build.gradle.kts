plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.vivian.mobilebc"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.vivian.mobilebc"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        val echoUpdateUrl = providers.gradleProperty("echoDataUpdateUrl").orElse("").get()
        require(echoUpdateUrl.isEmpty() || (echoUpdateUrl.startsWith("https://") &&
            echoUpdateUrl.none { it == '\"' || it == '\\' || it == '<' || it == '&' || it.isWhitespace() }))
        resValue("string", "echo_data_update_url", echoUpdateUrl)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
}

// Portrait tests read the bundled files directly, outside the compiled test classpath.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("src/main/assets/portrait"))
        .withPropertyName("portraitAssets")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:protocol"))
    implementation(project(":core:socket"))
    implementation(project(":data:storage"))
    implementation(project(":domain:bc-text"))
    implementation(project(":domain:messaging"))
    implementation(project(":domain:sync"))
    implementation(project(":domain:session-core"))
    implementation(project(":domain:session"))
    implementation(project(":domain:character"))
    implementation(project(":core:ui"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:rooms"))
    implementation(project(":feature:profile"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
