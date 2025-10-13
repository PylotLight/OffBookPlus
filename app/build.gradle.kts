import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("plugin.serialization") version "2.2.0"
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.devlight.offbookplus"
    compileSdk = 36

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                if (variant.buildType.name == "release") {
                    output.outputFileName = "OffBook+-v${variant.versionName}.apk"
                }
            }
    }

    defaultConfig {
        applicationId = "com.devlight.offbookplus"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.9"

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
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
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
    implementation(libs.room.compiler)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.androidx.sqlite.bundled)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
    implementation(libs.kotlinx.serialization.json)


    //
    implementation(libs.horologist.compose.layout)
    implementation(libs.google.horologist.media.ui)
    implementation(libs.google.horologist.media3.backend)
    implementation(libs.google.horologist.audio)
    implementation(libs.google.horologist.compose.material)

    implementation(libs.androidx.concurrent.futures.ktx)

    // --- Horologist (Wear OS Utilities)
    implementation(libs.androidx.appcompat)
    implementation(libs.core.ktx)

//    // --- Media3 (Core Media Framework)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.media3.ui)
    implementation(libs.media3.container)
    implementation(libs.media3.common)
    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.room.compiler)
    implementation(libs.androidx.room.ktx)

    // The m4b extractor can remain as 'implementation' now
    implementation(libs.media3.extractor.m4b)
//
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.navigation)
    ksp(libs.room.compiler)
}