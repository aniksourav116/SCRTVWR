import com.google.firebase.appdistribution.gradle.firebaseAppDistribution
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import java.io.File
import java.net.URI
import java.security.MessageDigest
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
        versionCode = 10
        versionName = "0.0.10"

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
    androidResources {
        // The face model is memory-mapped straight out of the APK, which only works uncompressed.
        noCompress += "tflite"
        // The notes on sourcing a model belong next to where it goes, not inside the shipped app.
        ignoreAssetsPatterns += "README.md"
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

// Optional: bake a face recognition model into the APK at build time.
//
// No model is downloaded unless a source is configured, because which model ships in the app is a
// licensing decision. Set it in gradle.properties:
//
//     faceModelUrl=https://example.com/mobilefacenet.tflite
//     faceModelSha256=<sha-256 of that file>
//
// or as SCRTVWR_FACE_MODEL_URL / SCRTVWR_FACE_MODEL_SHA256 in the environment. The checksum is
// optional but strongly advised: it is what stops the build silently picking up a changed file.
val faceModelUrl: String? = providers.gradleProperty("faceModelUrl").orNull
    ?: System.getenv("SCRTVWR_FACE_MODEL_URL")
val faceModelSha256: String? = providers.gradleProperty("faceModelSha256").orNull
    ?: System.getenv("SCRTVWR_FACE_MODEL_SHA256")
val faceModelFile = file("src/main/assets/mobilefacenet.tflite")

val downloadFaceModel = tasks.register("downloadFaceModel") {
    // Everything the action needs is captured as a plain local here. Reaching back into the script
    // from inside doLast is what the configuration cache refuses to serialise.
    val url = faceModelUrl
    val expected = faceModelSha256?.trim()
    val target = faceModelFile

    description = "Downloads the face recognition model into assets so it ships inside the APK."
    group = "build setup"
    onlyIf { url != null }
    outputs.file(target)

    doLast {
        if (url == null) return@doLast

        val alreadyCorrect = target.isFile && (
            expected.isNullOrBlank() ||
                run {
                    val digest = MessageDigest.getInstance("SHA-256")
                    target.inputStream().use { stream ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read <= 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    digest.digest()
                        .joinToString(separator = "") { value: Byte -> "%02x".format(value) }
                        .equals(expected, ignoreCase = true)
                }
            )
        if (alreadyCorrect) {
            logger.lifecycle("Face model already present at ${target.path}")
            return@doLast
        }

        target.parentFile.mkdirs()
        val temporary = File.createTempFile("face-model-", ".tmp", target.parentFile)
        try {
            logger.lifecycle("Downloading face model from $url")
            val connection = URI(url).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }

            if (!expected.isNullOrBlank()) {
                val actual = run {
                    val digest = MessageDigest.getInstance("SHA-256")
                    temporary.inputStream().use { stream ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read <= 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    digest.digest().joinToString(separator = "") { value: Byte -> "%02x".format(value) }
                }
                check(actual.equals(expected, ignoreCase = true)) {
                    "Downloaded face model does not match faceModelSha256 (got $actual); refusing to use it"
                }
            }

            check(temporary.renameTo(target) || (target.delete() && temporary.renameTo(target))) {
                "Unable to move the face model into ${target.path}"
            }
            logger.lifecycle("Face model packed: ${target.length()} bytes")
        } finally {
            temporary.delete()
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadFaceModel) }

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
    implementation(libs.mlkit.face.detection)
    implementation(libs.tensorflow.lite)
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
