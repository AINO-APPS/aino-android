plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
    .getOrElse("wss://next.aino.org.in")
val ainoContractVersion = providers.gradleProperty("AINO_CONTRACT_VERSION")
    .orElse(providers.environmentVariable("AINO_CONTRACT_VERSION"))
    .getOrElse("0.1.0")

android {
    namespace = "app.aino.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.aino.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "AINO_API_URL", ainoApiUrl.asBuildConfigString())
        buildConfigField("String", "AINO_WS_URL", ainoWsUrl.asBuildConfigString())
        buildConfigField("String", "AINO_CONTRACT_VERSION", ainoContractVersion.asBuildConfigString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.03.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(platform("androidx.compose:compose-bom:2025.03.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
