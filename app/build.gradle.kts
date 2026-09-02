import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The key shake-to-report posts issues with. Never in the repository: `local.properties` is
 * ignored by git, and CI hands it in from a repository secret. An empty string is a working
 * build — reports queue on the phone and go out from a later one that has the key.
 */
val reportToken: String = run {
    val local = rootProject.file("local.properties")
    val fromFile = if (local.exists()) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("reportToken")
    } else {
        null
    }
    fromFile ?: System.getenv("REPORT_TOKEN") ?: ""
}

// An empty token compiles and runs, which is exactly the problem: shake-to-report
// queues every report on the phone and waits for a build that has the key, and if no
// build ever has one it waits forever without saying so. This repository has no
// REPORT_TOKEN secret, so every release shipped so far is that build. Say it out loud
// on a release build rather than letting it stay silent.
if (reportToken.isEmpty() &&
    gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
) {
    logger.warn(
        "BrightOura: REPORT_TOKEN is empty. Shake-to-report will queue reports on the " +
            "phone and never post them. Set the REPORT_TOKEN repository secret."
    )
}

android {
    namespace = "com.gios.brightoura"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.gios.brightoura"
        minSdk = 29
        targetSdk = 35
        // CI overwrites both from the workflow run number; see .github/workflows/build.yml
        versionCode = 1
        versionName = "0.35.0"

        buildConfigField("String", "REPORT_TOKEN", "\"$reportToken\"")
        buildConfigField("String", "REPORT_REPO", "\"gi-os/light-reports\"")

        // The LPIII is arm64 only.
        ndk { abiFilters += "arm64-v8a" }
    }

    // The release key used to sit in this repository with its password written three
    // lines under it, so anyone at all could build an APK that Android would accept as
    // an update to this one. It is a CI secret now: the workflow decodes it to
    // keystore/brightoura.jks, and that path is gitignored so a local checkout cannot
    // commit it back.
    //
    // A build without the secret still compiles and still produces an APK. It is simply
    // not signed with the release key and will not install over one — which is the right
    // failure. A build that announces it is not the real thing beats one that quietly
    // is not.
    val keystoreFile = rootProject.file("keystore/brightoura.jks")
    val keystorePassword: String = System.getenv("KEYSTORE_PASSWORD") ?: ""
    val canSignRelease = keystoreFile.exists() && keystorePassword.isNotEmpty()

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "brightoura"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (canSignRelease) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Shake-to-report, the wheel, and the shared type/greys.
    implementation("com.gios:light-common:1.2.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // The protocol layer is pure Kotlin — framing, the auth proof, the hex — so it is tested here.
    testImplementation("junit:junit:4.13.2")
}
