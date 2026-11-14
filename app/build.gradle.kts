import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing: keystore.properties (not in git) names the keystore and the Keychain items
// that hold its passwords. See scripts/make-release-key.sh. Only a build that can make a release
// reads them, so debug builds, tests and lint never hold them.
val buildsRelease = gradle.startParameter.taskNames.any { name ->
    val task = name.substringAfterLast(':').lowercase()
    "release" in task || task in setOf("assemble", "build", "bundle")
}
val signingProps = rootProject.file("keystore.properties").takeIf { buildsRelease && it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

// An abbreviated task name (for example `aR`) does not match above and would give an unsigned APK
// without a message (PERF-HARDENING-10).
gradle.taskGraph.whenReady {
    if (signingProps == null && rootProject.file("keystore.properties").exists() && hasTask(":app:packageRelease")) {
        throw GradleException("Release build without signing: write the task name in full, for example assembleRelease.")
    }
}

val RELEASE_MIN_SDK = 35
val ombraMinSdk: Int = providers.gradleProperty("ombra.minSdk").map { it.trim().toInt() }.getOrElse(RELEASE_MIN_SDK)

fun keychain(service: String): String =
    providers.exec { commandLine("security", "find-generic-password", "-s", service, "-w") }
        .standardOutput.asText.get().trim()

android {
    namespace = "app.ombra"
    compileSdk = 37
    // Only used by AGP to strip and inspect native libraries; the Rust build pins its own NDK.
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "app.ombra"
        // Releases are for Android 15 and newer. The code runs from Android 11 (API 30): a test build for an older
        // phone takes -Pombra.minSdk=30. Lint checks only against the minSdk of the build, so run it with 30 too.
        minSdk = ombraMinSdk
        targetSdk = 37
        versionCode = 14
        // A test build for an older Android says so in its version, so a debug bundle tells it apart from a release.
        versionName = "0.2.12" + if (ombraMinSdk < RELEASE_MIN_SDK) "-api$ombraMinSdk" else ""
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Releases are arm64 only. An emulator test build takes -Pombra.abis=x86_64 (with that library in jniLibs).
            abiFilters += providers.gradleProperty("ombra.abis")
                .map { abis -> abis.split(',').map(String::trim).filter(String::isNotEmpty) }
                .getOrElse(listOf("arm64-v8a"))
        }
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = keychain(signingProps.getProperty("storePasswordKeychain"))
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = keychain(signingProps.getProperty("keyPasswordKeychain"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            vcsInfo { include = false }
            // Without a release key, produce an unsigned artifact instead of using the debug identity.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    androidResources {
        // The UI is English only. Ship only the English strings of the libraries, so that their labels match the UI.
        localeFilters += "en"
    }

    packaging {
        jniLibs {
            // Store the .so uncompressed and page-aligned so it maps straight from the APK.
            useLegacyPackaging = false
            // No code calls androidx.graphics.path (Compose Path.iterator()). After R8 only its JNI classes stay, with
            // no callers, so its System.loadLibrary never runs. If a library update starts to use Path.iterator(),
            // remove this line: without the .so that call stops the app on every Android version.
            excludes += "lib/*/libandroidx.graphics.path.so"
        }
        resources {
            // Licence files also in the library folders (META-INF/androidx/...): the Licences screen shows the
            // licence texts. The *.version files only tell each library version to a reader of the APK.
            excludes += setOf(
                "META-INF/versions/**", "META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/**/LICENSE*", "META-INF/**/NOTICE*", "META-INF/*.version",
                "DebugProbesKt.bin", "kotlin/**",
            )
        }
    }

    testOptions {
        // JVM unit tests run against android.jar stubs; Log and friends return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkDependencies = false
        // arm64-v8a only, by design (docs/DESIGN.md section 1).
        disable += "ChromeOsAbiSupport"
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

// Builds the Rust core into src/main/jniLibs before every variant. Gradle skips it while the
// sources and the .so are unchanged. -Pombra.skipRust=true turns it off (JVM tests, CI without NDK).
val buildRustCore = tasks.register<Exec>("buildRustCore") {
    description = "Builds libobscuravpn_client.so with scripts/build-rust.zsh."
    group = "build"
    enabled = providers.gradleProperty("ombra.skipRust").orNull != "true"

    val root = layout.settingsDirectory
    val script = root.file("scripts/build-rust.zsh")
    inputs.files(
        fileTree(root.dir("rust/obscura-core")) {
            include("src/**", "build.rs", "Cargo.toml", "Cargo.lock", "rust-toolchain.toml", ".cargo/config.toml")
        },
    ).withPropertyName("rustSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(script).withPropertyName("buildScript").withPathSensitivity(PathSensitivity.RELATIVE)
    // The script reads it: without this, a release build keeps the library of an earlier dev-fast build.
    inputs.property("rustProfile", providers.environmentVariable("OMBRA_RUST_PROFILE").orElse("release"))
    // The core links against the app's minSdk unless ANDROID_API says otherwise; a change rebuilds it.
    val androidApi = providers.environmentVariable("ANDROID_API").orElse(ombraMinSdk.toString())
    inputs.property("androidApi", androidApi)
    environment("ANDROID_API", androidApi.get())
    outputs.file(layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libobscuravpn_client.so"))
        .withPropertyName("library")

    workingDir(root.asFile)
    commandLine("zsh", script.asFile.absolutePath)
}

tasks.named("preBuild") { dependsOn(buildRustCore) }

// The signing config holds the passwords, so a build that signs stores no configuration cache entry.
if (signingProps != null) {
    tasks.matching { it.name in setOf("validateSigningRelease", "packageRelease", "signReleaseBundle") }.configureEach {
        notCompatibleWithConfigurationCache("the release signing config holds the Keychain passwords")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(kotlin("test-junit"))
}
