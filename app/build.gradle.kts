plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.ksp)
}

// CI writes this secret file before configuration. Local builds deliberately
// omit it; Firebase APIs fail closed at runtime while the rest of the app still
// builds and remains testable.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

// Backend targets are compiled into BuildConfig. The defaults point at the live
// platform (Railway project `aino-platform-next`) so a plain `assembleDebug`
// produces a working app; override per build with a Gradle property or an
// environment variable of the same name.
val ainoApiUrl = providers.gradleProperty("AINO_API_URL")
    .orElse(providers.environmentVariable("AINO_API_URL"))
    .getOrElse("https://next.aino.org.in/api")
val ainoWsUrl = providers.gradleProperty("AINO_WS_URL")
    .orElse(providers.environmentVariable("AINO_WS_URL"))
    // Realtime is a separate Railway role. Cloudflare routes /ws on the public
    // www host to it; next.aino.org.in points directly at the web role and does
    // not upgrade WebSockets.
    .getOrElse("wss://www.aino.org.in")
val ainoOtaBaseUrl = providers.gradleProperty("AINO_OTA_BASE_URL")
    .orElse(providers.environmentVariable("AINO_OTA_BASE_URL"))
    .getOrElse("https://cdn.aino.org.in")
val ainoContractVersion = providers.gradleProperty("AINO_CONTRACT_VERSION")
    .orElse(providers.environmentVariable("AINO_CONTRACT_VERSION"))
    .getOrElse("0.1.0")

// Release signing is driven entirely by environment variables so the keystore
// never has to exist inside the working tree. When they are absent (a normal
// developer machine) the release build stays unsigned rather than silently
// falling back to the debug key, and `android-release.yml` fails the build if
// the resulting APK is not signed by `CN=AINO`.
// NOTE: these are deliberately NOT named `keyAlias`/`keyPassword`. Inside a
// `signingConfigs.create("release") { }` block the receiver is a SigningConfig,
// which already declares properties with those names, so `keyPassword = keyPassword`
// would silently assign the property to itself and the build would fail with
// "SigningConfig \"release\" is missing required property \"keyPassword\"".
val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank() &&
    !releaseKeystorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "app.aino.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.aino.mobile"
        minSdk = 26
        targetSdk = 35
        // Android compares versionCode, not versionName, when deciding whether an
        // APK may replace an installed one, so it is derived mechanically from the
        // version: X.Y.Z -> X*1_000_000 + Y*1_000 + Z (each part 0..999).
        // `android-release.yml` re-derives this from the tag and fails on a mismatch.
        // Written without digit separators so the release workflow can parse it.
        versionCode = 5000 // 0.5.0
        versionName = "0.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "AINO_API_URL", ainoApiUrl.asBuildConfigString())
        buildConfigField("String", "AINO_WS_URL", ainoWsUrl.asBuildConfigString())
        buildConfigField("String", "AINO_OTA_BASE_URL", ainoOtaBaseUrl.asBuildConfigString())
        buildConfigField("String", "AINO_CONTRACT_VERSION", ainoContractVersion.asBuildConfigString())
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // Deliberately null: an unsigned artifact fails the release
                // workflow's signature check loudly instead of shipping a
                // debug-signed build that Android would refuse to update later.
                null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.webrtc.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.mlkit.face.detection)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.video)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testReleaseImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.room.testing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
