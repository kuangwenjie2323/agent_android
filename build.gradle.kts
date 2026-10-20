plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.wrapper {
    gradleVersion = "9.8.0"
    distributionType = Wrapper.DistributionType.BIN
}
