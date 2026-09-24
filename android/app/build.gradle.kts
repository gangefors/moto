// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

import java.io.FileOutputStream
import javax.inject.Inject
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
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

/**
 * Copies a region file into the APK's assets as `regions/m0.region`, for
 * testing (production will download regions; ADR-0005). The file comes from
 * the `moto.regionFile` Gradle property or the `MOTO_REGION_FILE` environment
 * variable (CI builds it with moto-regionbuild); without one the app starts
 * without a region.
 */
abstract class BundleRegion : DefaultTask() {
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val regionFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val region = regionFile.orNull?.asFile
        if (region == null) {
            logger.warn("No region file bundled; set -Pmoto.regionFile or MOTO_REGION_FILE.")
            return
        }
        region.copyTo(out.resolve("regions/m0.region"))
    }
}

val bundleRegion = tasks.register<BundleRegion>("bundleRegion") {
    group = "moto"
    val path = providers.gradleProperty("moto.regionFile")
        .orElse(providers.environmentVariable("MOTO_REGION_FILE"))
    regionFile.fileProvider(path.map { file(it) })
}

/**
 * Writes the app's licence notices (ADR-0004) into the APK's assets as
 * `licenses/third_party.txt`: the app's own licence, then every Rust crate
 * linked into moto-ffi for the Android targets and every library on the
 * variant's runtime classpath, with their licence texts. Built by
 * `.github/scripts/third_party.py` at every build, so it always matches
 * what ships; a dependency without a licence text fails the build.
 */
abstract class ThirdPartyLicenses @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal
    abstract val coreDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val coreSources: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val script: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val keptLicences: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val appLicence: RegularFileProperty

    /** The variant's runtime dependency graph. */
    @get:Input
    abstract val runtimeGraph: Property<ResolvedComponentResult>

    /** Its archives: declared so Gradle has them in its cache first. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val runtimeFiles: ConfigurableFileCollection

    @get:Input
    abstract val gradleCache: Property<String>

    @get:Input
    abstract val rustTargets: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val core = coreDir.get().asFile
        val work = temporaryDir
        val out = outputDir.get().asFile
        out.deleteRecursively()

        // group:name:version of every library in the graph.
        val modules = sortedSetOf<String>()
        val seen = mutableSetOf<ResolvedComponentResult>()
        val queue = ArrayDeque(listOf(runtimeGraph.get()))
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            (c.id as? ModuleComponentIdentifier)?.let { modules += "${it.group}:${it.module}:${it.version}" }
            c.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
        }
        val maven = work.resolve("maven.txt").apply { writeText(modules.joinToString("\n", postfix = "\n")) }

        fun cargoTo(file: File, vararg args: String) = file.also {
            FileOutputStream(it).use { stream ->
                exec.exec {
                    workingDir = core
                    commandLine(listOf("cargo") + args)
                    standardOutput = stream
                }
            }
        }
        val trees = rustTargets.get().map { target ->
            cargoTo(
                work.resolve("tree-$target.txt"),
                "tree", "--locked", "-q", "-p", "moto-ffi", "-e", "normal,no-proc-macro",
                "--target", target, "--prefix", "none", "--format", "{p}|{l}",
            )
        }
        val metadata = cargoTo(work.resolve("metadata.json"), "metadata", "--locked", "-q", "--format-version", "1")

        exec.exec {
            commandLine(
                listOf("python3", script.get().asFile.absolutePath) +
                    trees.flatMap { listOf("--cargo-tree", it.absolutePath) } +
                    listOf(
                        "--cargo-metadata", metadata.absolutePath,
                        "--maven", maven.absolutePath,
                        "--gradle-cache", gradleCache.get(),
                        "--licenses", keptLicences.get().asFile.absolutePath,
                        "--app-licence", appLicence.get().asFile.absolutePath,
                        "--out", out.resolve("licenses/third_party.txt").absolutePath,
                    ),
            )
        }
    }
}

// The Rust targets cargo-ndk builds for `abis`.
val rustTargetOfAbi = mapOf("arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android")

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(uniffiBindings, UniffiBindgen::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(bundleRegion, BundleRegion::outputDir)
        val licences = tasks.register<ThirdPartyLicenses>("${variant.name}ThirdPartyLicenses") {
            group = "moto"
            coreDir.set(rustCoreDir)
            coreSources.from(rustSources)
            script.set(rootProject.file("../.github/scripts/third_party.py"))
            keptLicences.set(file("licenses"))
            appLicence.set(rootProject.file("../LICENSE"))
            runtimeGraph.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent)
            runtimeFiles.from(variant.runtimeConfiguration)
            gradleCache.set(gradle.gradleUserHomeDir.resolve("caches/modules-2/files-2.1").absolutePath)
            rustTargets.set(abis.map { rustTargetOfAbi.getValue(it) })
            // cargo-ndk has fetched the crates whose licence files are read.
            dependsOn(cargoNdkBuild)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(licences, ThirdPartyLicenses::outputDir)
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

    testImplementation(libs.junit)
}
