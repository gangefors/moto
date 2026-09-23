// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Rust core (../../core) is built by cargo-ndk into src/main/jniLibs, and
// its UniFFI Kotlin bindings are generated into build/. Both run before
// every build, so `./gradlew assembleDebug` always matches the core.
val rustCoreDir: File = rootProject.file("../core")
val jniLibsDir: File = file("src/main/jniLibs")
val rustSources = files(
    rustCoreDir.resolve("Cargo.toml"),
    rustCoreDir.resolve("Cargo.lock"),
    rustCoreDir.resolve("moto-core"),
    rustCoreDir.resolve("moto-ffi"),
)
val abis = listOf("arm64-v8a", "x86_64")

android {
    namespace = "se.gangefors.moto"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "se.gangefors.moto"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += abis }
    }

    signingConfigs {
        getByName("debug") {
            // CI passes a stable debug key so each APK installs over the last.
            providers.environmentVariable("MOTO_DEBUG_KEYSTORE").orNull?.let { storeFile = file(it) }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "rust"
    description = "Builds moto-ffi for Android with cargo-ndk."
    workingDir = rustCoreDir
    val ndkDir = androidComponents.sdkComponents.ndkDirectory
    doFirst { environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath) }
    commandLine(
        listOf("cargo", "ndk") +
            abis.flatMap { listOf("-t", it) } +
            listOf("-o", jniLibsDir.absolutePath, "build", "--release", "-p", "moto-ffi"),
    )
    inputs.files(rustSources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(jniLibsDir)
}

/** Generates the UniFFI Kotlin bindings for moto-ffi from a host build of the library. */
abstract class UniffiBindgen @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal
    abstract val coreDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val coreSources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val core = coreDir.get().asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        // bindgen reads the UniFFI metadata from the host library.
        exec.exec {
            workingDir = core
            commandLine("cargo", "build", "-p", "moto-ffi")
        }
        exec.exec {
            workingDir = core
            commandLine(
                "cargo", "run", "-p", "moto-ffi", "--features", "cli", "--bin", "uniffi-bindgen", "--",
                "generate", "--library", core.resolve("target/debug/libmoto_ffi.so").absolutePath,
                "--language", "kotlin", "--no-format", "--out-dir", out.absolutePath,
            )
        }
    }
}

val uniffiBindings = tasks.register<UniffiBindgen>("uniffiBindings") {
    group = "rust"
    coreDir.set(rustCoreDir)
    coreSources.from(rustSources)
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(uniffiBindings, UniffiBindgen::outputDir)
    }
}

tasks.named("preBuild") { dependsOn(cargoNdkBuild) }

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.maplibre.android)
    // UniFFI's Kotlin bindings load the native library through JNA.
    implementation("${libs.jna.get()}@aar")
}
