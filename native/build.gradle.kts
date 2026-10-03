import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Pin both artifacts so native passkey support does not depend on local cache contents.
val credentialVersion = "1.6.0"

// The same external-only mechanism as :app. Debug uses Android's default key.
val signingVariable = "AGENTWEB_ANDROID_KEYSTORE_PROPERTIES"
val signingPath = providers.environmentVariable(signingVariable).orNull?.takeIf(String::isNotBlank)
val releaseProperties = Properties()
var releaseStore: File? = null
if (signingPath != null) {
    val propertiesFile = file(signingPath)
    if (!propertiesFile.isFile || !propertiesFile.canRead()) {
        throw GradleException("$signingVariable must point to a readable properties file.")
    }
    try {
        propertiesFile.inputStream().use { releaseProperties.load(it) }
    } catch (_: Exception) {
        throw GradleException("Cannot read signing properties from $signingVariable.")
    }
    for (key in listOf("storeFile", "storePassword", "keyAlias", "keyPassword")) {
        if (releaseProperties.getProperty(key).isNullOrBlank()) {
            throw GradleException("The external Android signing properties must define '$key'.")
        }
    }
    val configuredStore = File(releaseProperties.getProperty("storeFile"))
    releaseStore = if (configuredStore.isAbsolute) configuredStore else
        File(propertiesFile.parentFile, configuredStore.path)
    if (releaseStore?.isFile != true || releaseStore?.canRead() != true) {
        throw GradleException("The external signing storeFile must identify a readable keystore.")
    }
}
val verifyReleaseSigningEnvironment by tasks.registering {
    group = "verification"
    doLast {
        if (signingPath == null) throw GradleException(
            "Release signing requires AGENTWEB_ANDROID_KEYSTORE_PROPERTIES. No unsigned release is produced."
        )
    }
}
tasks.configureEach {
    if (name != "verifyReleaseSigningEnvironment" && name.contains("release", ignoreCase = true)) {
        dependsOn(verifyReleaseSigningEnvironment)
    }
}

android {
    namespace = "com.karewinkcloud.agentweb.client"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    defaultConfig {
        applicationId = "com.karewinkcloud.agentweb.client"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0"
    }
    signingConfigs {
        if (signingPath != null) create("release") {
            storeFile = releaseStore
            storePassword = releaseProperties.getProperty("storePassword")
            keyAlias = releaseProperties.getProperty("keyAlias")
            keyPassword = releaseProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            if (signingPath != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    bundle { language { enableSplit = false } }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    sourceSets.getByName("main").kotlin.srcDir("src/passkey/java")
    sourceSets.getByName("test").resources.srcDir("../conformance/fixtures")
    testOptions.unitTests.isReturnDefaultValues = true
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}


dependencies {
    implementation("androidx.credentials:credentials:$credentialVersion")
    implementation("androidx.credentials:credentials-play-services-auth:$credentialVersion")
    val composeBom = platform("androidx.compose:compose-bom:2025.08.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.commonmark:commonmark:0.25.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
