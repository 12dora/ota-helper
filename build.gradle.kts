import dev.detekt.gradle.extensions.DetektExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    apply(plugin = "dev.detekt")
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        parallel.set(true)
        // `./gradlew detekt -PdetektFix` applies ktlint auto-corrections locally; CI never rewrites.
        autoCorrect.set(providers.gradleProperty("detektFix").isPresent)
    }
    dependencies {
        add("detektPlugins", rootProject.libs.detekt.ktlint)
    }
}
