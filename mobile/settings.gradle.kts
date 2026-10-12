// VectorCraft for Android, ChromeOS and iPadOS: a Kotlin Multiplatform shell with a Compose UI
// over the Rust engine (crates/ffi). See docs/mobile.md.
rootProject.name = "vectorcraft-mobile"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared")
include(":androidApp")
