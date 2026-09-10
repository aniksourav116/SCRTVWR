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

// Release signing is optional: builds still work without a keystore, they just stay
// debug-signed. Values come from the environment first (CI) then keystore.properties.
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
        versionCode = 8
        versionName = "0.0.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("storePassword", "SCRTVWR_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SCRTVWR_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SCRTVWR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
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
    implementation(platform(libs.firebase.bom))
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
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.messaging)
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
