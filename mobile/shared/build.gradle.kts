import gobley.gradle.GobleyHost
import gobley.gradle.cargo.dsl.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The shared module: the engine bindings (generated from crates/ffi by Gobley) and the whole
// Compose UI. Android, iOS and the desktop JVM (a development harness) all run this code.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.atomicfu)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.library)
    alias(libs.plugins.gobley.cargo)
    alias(libs.plugins.gobley.uniffi)
}

cargo {
    // The engine bridge (crates/ffi) linked as one native library: see rust/Cargo.toml.
    packageDirectory = layout.projectDirectory.dir("rust")
    // The desktop harness loads the host's build only.
    builds.jvm {
        embedRustLibrary = (rustTarget == GobleyHost.current.rustTarget)
    }
}

uniffi {
    generateFromLibrary {
        namespace = "vectorcraft_ffi"
        packageName = "com.appthere.vectorcraft.engine"
    }
}

kotlin {
    // JVM 17 bytecode (see the targets) from whichever JDK runs Gradle: 17 or later, such as the
    // JDK Android Studio bundles.

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "VectorCraftShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

android {
    namespace = "com.appthere.vectorcraft.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "com.appthere.vectorcraft.MainKt"
    }
}

// `./gradlew :shared:run -Dvectorcraft.window=390x844`: the desktop harness at a phone's size.
tasks.withType<JavaExec>().configureEach {
    System.getProperty("vectorcraft.window")?.let { systemProperty("vectorcraft.window", it) }
}
