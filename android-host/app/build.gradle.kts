import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties

/** NDK release used for both the Android build and the Rust cross-compile. */
val androidNdkVersion = "26.1.10909125"

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.androidhost"
    compileSdk = 36
    ndkVersion = androidNdkVersion
    defaultConfig {
        applicationId = "com.example.androidhost"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}



dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
  implementation(libs.androidx.biometric)

  // Generated protobuf message classes. javalite is the Android-sized runtime.
  implementation(libs.protobuf.javalite)
}

// ---------------------------------------------------------------------------
// Protobuf generation
//
// protobuf-gradle-plugin is not used: 0.9.5 casts the Android extension to AGP's
// removed BaseExtension and fails outright under AGP 9, and 0.10.0 applies but no
// longer offers a way to point an Android source set at .proto files living outside
// the module. protoc is therefore invoked directly. The compiler is pinned to the
// same version as the protobuf-javalite runtime, so generated code and runtime can
// never drift apart.
//
// The .proto files are read from rust-receiver/zc-protocol/proto, which is the single
// canonical copy shared with the Rust receiver. They are never duplicated here.
// ---------------------------------------------------------------------------

/** Maven classifier for the protoc binary matching the machine running the build. */
val protocClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osPart = when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "osx"
        else -> "linux"
    }
    val archPart = when {
        arch.contains("aarch64") || arch.contains("arm64") -> "aarch_64"
        arch.contains("64") -> "x86_64"
        else -> "x86_32"
    }
    "$osPart-$archPart"
}

val protocTool: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    protocTool("com.google.protobuf:protoc:${libs.versions.protobuf.get()}:$protocClassifier@exe")
}

/**
 * Runs protoc over every .proto in [protoDir], emitting protobuf-lite Java sources.
 *
 * Uses injected ExecOperations rather than Project.exec so the task stays compatible
 * with Gradle's configuration cache, which this build has enabled.
 */
abstract class GenerateProtoJava : DefaultTask() {

    @get:InputDirectory
    abstract val protoDir: DirectoryProperty

    @get:InputFiles
    abstract val protocBinary: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: org.gradle.process.ExecOperations

    @TaskAction
    fun generate() {
        val root = protoDir.get().asFile
        val protos = root.listFiles { f: java.io.File -> f.isFile && f.extension == "proto" }
            ?.sortedBy { it.name }
            .orEmpty()
        if (protos.isEmpty()) {
            throw GradleException("No .proto files found in $root")
        }

        val out = outputDir.get().asFile
        // Wipe first so a renamed or deleted message cannot leave a stale .java behind.
        out.deleteRecursively()
        out.mkdirs()

        val protoc = protocBinary.singleFile
        protoc.setExecutable(true)

        execOps.exec {
            commandLine(
                buildList {
                    add(protoc.absolutePath)
                    add("--proto_path=${root.absolutePath}")
                    add("--java_out=lite:${out.absolutePath}")
                    addAll(protos.map { it.absolutePath })
                }
            )
        }
        logger.lifecycle("protoc generated lite sources for ${protos.size} proto files")
    }
}

val generateProtoJava = tasks.register<GenerateProtoJava>("generateProtoJava") {
    protoDir.set(layout.projectDirectory.dir("../../rust-receiver/zc-protocol/proto"))
    protocBinary.from(protocTool)
    outputDir.set(layout.buildDirectory.dir("generated/source/proto/java"))
}

// ---------------------------------------------------------------------------
// Native QUIC server
//
// ../rust_quic_server holds the QUIC server, the pairing handshake and the PSK
// verification. It used to be built by hand with `cargo ndk` and the resulting .so copied
// into src/main/jniLibs, with the binary committed to git. That meant an app built without
// those manual steps silently shipped whatever .so happened to be checked in — including,
// for a while, a build whose authentication accepted every token. The .so files are gone
// from the repository and the crate is built here instead, so `./gradlew :app:assembleDebug`
// alone is enough and can only ever package a library built from the current sources.
//
// If the toolchain is missing the build fails and names what to install. There is
// deliberately no fallback to a prebuilt library.
// ---------------------------------------------------------------------------

/** ABI directory name -> Rust target triple. Both must be present for a build to pass. */
val rustAbiTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
)

abstract class CargoNdkBuild : DefaultTask() {

    /** Crate sources. `target/` is excluded so its build output is not hashed. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val crateSources: ConfigurableFileCollection

    @get:Internal
    abstract val crateDir: DirectoryProperty

    /** ABI directory names passed to `cargo ndk -t`. */
    @get:Input
    abstract val abis: ListProperty<String>

    /** Rust target triples the ABIs above resolve to, checked against rustup. */
    @get:Input
    abstract val rustTargets: ListProperty<String>

    /** NDK root. Resolved at configuration time so the task stays cacheable. */
    @get:Input
    abstract val ndkPath: Property<String>

    /** NDK version, quoted back to the developer when the NDK is missing. */
    @get:Input
    abstract val ndkVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: org.gradle.process.ExecOperations

    /** Runs a command, returning its exit code and combined output. */
    private fun run(vararg command: String): Pair<Int, String> {
        val sink = ByteArrayOutputStream()
        return try {
            val result = execOps.exec {
                commandLine(*command)
                standardOutput = sink
                errorOutput = sink
                isIgnoreExitValue = true
            }
            result.exitValue to sink.toString().trim()
        } catch (e: Exception) {
            // Thrown when the executable itself cannot be started, e.g. cargo not on PATH.
            -1 to (e.message ?: "could not start ${command.first()}")
        }
    }

    @TaskAction
    fun build() {
        val ndk = File(ndkPath.get())
        val wanted = ndkVersion.get()
        if (!ndk.isDirectory) {
            throw GradleException(
                "Android NDK not found at $ndk.\n" +
                    "Install it with: sdkmanager \"ndk;$wanted\"\n" +
                    "  (Android Studio: Settings > Languages & Frameworks > Android SDK > " +
                    "SDK Tools > NDK (Side by side), version $wanted)\n" +
                    "Or point ANDROID_NDK_HOME at an existing installation."
            )
        }

        val (cargoStatus, cargoOutput) = run("cargo", "--version")
        if (cargoStatus != 0) {
            throw GradleException(
                "cargo was not found on PATH, so the native QUIC server cannot be built.\n" +
                    "Install the Rust toolchain from https://rustup.rs and reopen your shell.\n" +
                    "Details: $cargoOutput"
            )
        }

        val (ndkStatus, ndkOutput) = run("cargo", "ndk", "--version")
        if (ndkStatus != 0) {
            throw GradleException(
                "cargo-ndk was not found, so the native QUIC server cannot be built.\n" +
                    "Install it with: cargo install cargo-ndk --version 4.1.2 --locked\n" +
                    "Details: $ndkOutput"
            )
        }

        val (rustupStatus, installedTargets) = run("rustup", "target", "list", "--installed")
        if (rustupStatus == 0) {
            val missing = rustTargets.get().filterNot { installedTargets.contains(it) }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "The Rust standard library is not installed for: ${missing.joinToString(", ")}\n" +
                        "Install it with: rustup target add ${missing.joinToString(" ")}"
                )
            }
        } else {
            logger.warn(
                "rustup is not available, so installed Rust targets could not be checked. " +
                    "If the build fails below, run: rustup target add ${rustTargets.get().joinToString(" ")}"
            )
        }

        val out = outputDir.get().asFile
        // Wipe first so an ABI dropped from the list cannot leave a stale .so to be packaged.
        out.deleteRecursively()
        out.mkdirs()

        val command = buildList {
            add("cargo")
            add("ndk")
            abis.get().forEach { add("-t"); add(it) }
            add("-o")
            add(out.absolutePath)
            add("build")
            // Always release: a debug build of rustls/quinn is slow enough to be visible
            // as stutter in the video path, and the crate is not stepped through anyway.
            add("--release")
        }

        logger.lifecycle("Building the native QUIC server for ${abis.get().joinToString(", ")}")

        val sink = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine(command)
            workingDir = crateDir.get().asFile
            environment("ANDROID_NDK_HOME", ndk.absolutePath)
            standardOutput = sink
            errorOutput = sink
            isIgnoreExitValue = true
        }

        val log = sink.toString().trim()
        if (result.exitValue != 0) {
            throw GradleException("cargo ndk failed (exit ${result.exitValue}):\n$log")
        }
        if (log.isNotEmpty()) {
            logger.info(log)
        }

        val missingLibs = abis.get().filterNot {
            File(out, "$it/librust_quic_server.so").isFile
        }
        if (missingLibs.isNotEmpty()) {
            throw GradleException(
                "cargo ndk reported success but produced no library for: " +
                    missingLibs.joinToString(", ")
            )
        }
        logger.lifecycle("Native QUIC server built for ${abis.get().joinToString(", ")}")
    }
}

val rustCrateDir = layout.projectDirectory.dir("../rust_quic_server")

/**
 * Locates the NDK without going through the Android extension.
 *
 * AGP 9 dropped `android.ndkDirectory` along with the rest of BaseExtension, so the usual
 * locations are checked directly: the NDK environment variables first, then the SDK root
 * from the environment or local.properties, plus `ndk/<version>`. Returns a best-guess
 * path even when nothing exists, so the task action can report exactly what it looked for.
 */
fun resolveNdkDirectory(): String {
    System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }?.let { return it }
    System.getenv("ANDROID_NDK_ROOT")?.takeIf { it.isNotBlank() }?.let { return it }

    val sdkRoot = sequenceOf(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        rootProject.file("local.properties")
            .takeIf { it.isFile }
            ?.let { file ->
                Properties().apply { file.inputStream().use { stream -> load(stream) } }
                    .getProperty("sdk.dir")
            },
    ).filterNotNull().firstOrNull { it.isNotBlank() }
        ?: return "<no Android SDK found: set ANDROID_HOME or sdk.dir in local.properties>"

    return File(File(sdkRoot, "ndk"), androidNdkVersion).absolutePath
}

val buildRustQuicServer = tasks.register<CargoNdkBuild>("buildRustQuicServer") {
    description = "Cross-compiles ../rust_quic_server for every packaged ABI."
    group = "build"

    crateDir.set(rustCrateDir)
    crateSources.from(
        rustCrateDir.file("Cargo.toml"),
        rustCrateDir.file("Cargo.lock"),
        rustCrateDir.file("build.rs"),
        fileTree(rustCrateDir.dir("src")),
    )
    abis.set(rustAbiTargets.keys.toList())
    rustTargets.set(rustAbiTargets.values.toList())
    ndkPath.set(resolveNdkDirectory())
    ndkVersion.set(androidNdkVersion)
    outputDir.set(layout.buildDirectory.dir("rustJniLibs"))
}

// `./gradlew :app:assembleDebug` on its own has to be sufficient, so the native build is
// pinned ahead of everything else rather than relying on the source wiring below alone.
tasks.named("preBuild") {
    dependsOn(buildRustQuicServer)
}

androidComponents {
    onVariants { variant ->
        // The modern AGP source API: registers the directory and the task that fills
        // it, so ordering and up-to-date checks are wired automatically. The older
        // sourceSets.java.srcDir(...) route is deprecated in AGP 9.
        variant.sources.java?.addGeneratedSourceDirectory(
            generateProtoJava,
            GenerateProtoJava::outputDir
        )
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            buildRustQuicServer,
            CargoNdkBuild::outputDir
        )
    }
}
