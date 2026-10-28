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
        minSdk = 36
        targetSdk = 37
        versionCode = 8
        versionName = "0.2.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
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

    packaging {
        jniLibs {
            // Store the .so uncompressed and page-aligned so it maps straight from the APK.
            useLegacyPackaging = false
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
