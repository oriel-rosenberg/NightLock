plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Optional: keep build output under a short path, for Windows' 260-character limit.
// build.cmd passes this; without it Gradle uses the usual build/ folders.
providers.gradleProperty("nightlock.buildRoot").orNull?.let { root ->
    allprojects {
        layout.buildDirectory.set(file("$root/${project.name}"))
    }
}
