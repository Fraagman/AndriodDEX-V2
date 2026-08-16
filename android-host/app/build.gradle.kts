plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.androidhost"
    compileSdk = 36
    ndkVersion = "26.1.10909125"
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

androidComponents {
    onVariants { variant ->
        // The modern AGP source API: registers the directory and the task that fills
        // it, so ordering and up-to-date checks are wired automatically. The older
        // sourceSets.java.srcDir(...) route is deprecated in AGP 9.
        variant.sources.java?.addGeneratedSourceDirectory(
            generateProtoJava,
            GenerateProtoJava::outputDir
        )
    }
}
