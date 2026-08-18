plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.devlight.offbookplus"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.devlight.offbookplus"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.0"
    }

    signingConfigs {
        create("release") {
            fun prop(name: String): String? =
                (project.findProperty(name) ?:
                rootProject.file("local.properties").takeIf { it.exists() }
                    ?.readLines()
                    ?.firstOrNull { it.startsWith("$name=") }
                    ?.substringAfter("=")) as? String

            storeFile = file(prop("APP_KEY_FILE") ?: "release.keystore")
            storePassword = prop("APP_KEYSTORE_PASSWORD") ?: ""
            keyAlias = prop("APP_KEYSTORE_ALIAS") ?: ""
            keyPassword = prop("APP_KEY_PASSWORD") ?: ""
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
    }
    lint {
        disable += "InvalidFragmentVersionForActivityResult"
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("OffBook+-v${output.versionName.get()}.apk")
        }
    }
}
configurations.all {
    exclude(group = "com.intellij", module = "annotations")
}

dependencies {
    implementation(libs.play.services.wearable)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.material)
    implementation(libs.compose.foundation)
    implementation(libs.wear.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.androidx.sqlite.bundled)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.horologist.compose.layout)
    implementation(libs.google.horologist.media.ui)
    implementation(libs.google.horologist.media3.backend)
    implementation(libs.google.horologist.audio)
    implementation(libs.google.horologist.compose.material)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.core.ktx)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.inspector)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.media3.ui)
    implementation(libs.media3.container)
    implementation(libs.media3.common)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.media3.extractor.m4b)

    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.navigation)
    ksp(libs.room.compiler)
}