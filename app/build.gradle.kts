import com.google.firebase.appdistribution.gradle.firebaseAppDistribution
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.firebase.appdistribution)
}

// Release signing comes from the environment first, then keystore.properties.
// Without an upload key, release stays unsigned and never uses the debug key.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { stream -> load(stream) }
    }
}

fun signingValue(property: String, environmentVariable: String): String? =
    System.getenv(environmentVariable) ?: keystoreProperties.getProperty(property)

val releaseStorePath = signingValue("storeFile", "SCRTVWR_STORE_FILE")
val releaseKeystore = releaseStorePath?.let(rootProject::file)?.takeIf { it.exists() }

android {
    namespace = "srv.scrtvwr"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "srv.scrtvwr"
        minSdk = 24
        targetSdk = 37
        versionCode = 13
        versionName = "0.0.13"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Checked-in debug key so debug builds from any machine install over each other.
        // Debug keys aren't secret; never use this one for release.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("storePassword", "SCRTVWR_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SCRTVWR_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SCRTVWR_KEY_PASSWORD")
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("personal") {
            dimension = "distribution"
            buildConfigField("boolean", "PERSONAL_EDITION", "true")
        }
        create("play") {
            dimension = "distribution"
            applicationIdSuffix = ".play"
            buildConfigField("boolean", "PERSONAL_EDITION", "false")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            configure<CrashlyticsExtension> {
                // Debug builds report crashes at runtime but never need a mapping file.
                mappingFileUploadEnabled = false
            }
        }
        release {
            optimization {
                enable = false
            }
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            configure<CrashlyticsExtension> {
                // Only produces a useful mapping once optimization.enable is turned on.
                mappingFileUploadEnabled = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// App Distribution defaults for every build type. The app id comes from google-services.json.
// Credentials resolve in this order: FIREBASE_SERVICE_ACCOUNT -> gcloud/firebase CLI login.
firebaseAppDistribution {
    artifactType = "APK"
    releaseNotesFile = rootProject.file("distribution/release-notes.txt").path
    // Opt in per invocation: -PfirebaseTesterGroups=qa,internal
    providers.gradleProperty("firebaseTesterGroups").orNull?.let { groups = it }
    System.getenv("FIREBASE_SERVICE_ACCOUNT")?.let { serviceCredentialsFile = it }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    "personalImplementation"(platform(libs.firebase.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    "personalImplementation"(libs.firebase.analytics)
    "personalImplementation"(libs.firebase.crashlytics)
    "personalImplementation"(libs.firebase.messaging)
    implementation(libs.timber)
    implementation(libs.junrar)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Play has no Firebase SDKs or Firebase application registration.
tasks.configureEach {
    if (name.contains("Play") &&
        (name.endsWith("GoogleServices") || name.contains("Crashlytics") || name.startsWith("appDistribution"))) {
        enabled = false
    }
    if (name in setOf("packagePlayRelease", "packagePlayReleaseBundle", "bundlePlayRelease", "assemblePlayRelease")) {
        doFirst {
            val configuredKeystore = releaseKeystore
            check(configuredKeystore != null && configuredKeystore.canonicalFile != file("debug.keystore").canonicalFile &&
                listOf("storePassword" to "SCRTVWR_STORE_PASSWORD", "keyAlias" to "SCRTVWR_KEY_ALIAS",
                    "keyPassword" to "SCRTVWR_KEY_PASSWORD").all { (property, env) -> !signingValue(property, env).isNullOrBlank() }) {
                "Configure a production upload keystore before packaging Play release. See docs/PLAY_RELEASE.md."
            }
        }
    }
}
