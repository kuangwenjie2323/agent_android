import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing credentials stay outside the repository. Debug uses Android's default key.
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
val verifyReleaseSigningEnvironment = tasks.register("verifyReleaseSigningEnvironment") {
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
        versionCode = 7
        versionName = "0.7.0"
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
    sourceSets.getByName("test").resources.srcDir("../conformance/fixtures")
    testOptions.unitTests.isReturnDefaultValues = true
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}


dependencies {
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.browser)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.commonmark)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
