import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceTask
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.ktlint)

    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm()

    // Suppress expect/actual beta warnings across all targets as suggested by KT-61573
    targets.all {
        compilations.all {
            compilerOptions.configure {
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(compose.materialIconsExtended)
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")

            implementation(compose.material3)

            val voyagerVersion: String = "1.1.0-beta03"
            implementation("cafe.adriel.voyager:voyager-navigator:$voyagerVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            // Networking and JSON for GoogleAuthDesktop
            implementation("io.ktor:ktor-client-core:3.0.1")
            implementation("io.ktor:ktor-client-cio:3.0.1")
        }
    }
}

ktlint {
    android.set(true)
    verbose.set(true)
    outputToConsole.set(true)
    filter {
        include("**/src/**")
        exclude("**/build/**")
        exclude { it.file.path.contains("/build/") }
        exclude("**/build/generated/**")
        exclude { it.file.path.contains("/build/generated/") }
    }
}

tasks.withType<SourceTask>().configureEach {
    if (name.startsWith("ktlint")) {
        setSource(files("src"))
    }
}

tasks.matching { it.name.startsWith("ktlint") && it.name.endsWith("SourceSetCheck") }.configureEach {
    (this as? SourceTask)?.setSource(files("src"))
}

tasks.matching { it.name.startsWith("runKtlintCheckOver") || it.name.startsWith("runKtlintFormatOver") }
    .configureEach {
        val setter =
            this.javaClass.methods.firstOrNull { method ->
                method.name == "setSource" && method.parameterCount == 1
            }
        setter?.invoke(this, files("src"))
    }

afterEvaluate {
    tasks.matching { it.name.startsWith("runKtlintCheckOver") || it.name.startsWith("runKtlintFormatOver") }
        .configureEach {
            val setter =
                this.javaClass.methods.firstOrNull { method ->
                    method.name == "setSource" && method.parameterCount == 1
                }
            setter?.invoke(this, files("src"))
        }
}

android {
    namespace = "org.strata"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.strata"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        disable += setOf("NullSafeMutableLiveData")
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "org.strata.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "org.strata"
            packageVersion = "1.0.0"

            macOS {
                iconFile.set(project.file("src/commonMain/composeResources/drawable/StrataNoText.png"))
                bundleID = "org.strata.app"
            }

            windows {
                iconFile.set(project.file("src/commonMain/composeResources/drawable/StrataNoText.png"))
            }

            linux {
                iconFile.set(project.file("src/commonMain/composeResources/drawable/StrataNoText.png"))
            }
        }
    }
}

afterEvaluate {
    tasks.named<JavaExec>("run").configure {
        jvmArgs(
            "-Dapple.awt.application.name=Strata",
            "-Xdock:name=Strata",
        )
        // load from local .env-like file or hardcode while testing
        environment("GOOGLE_DESKTOP_CLIENT_ID", System.getenv("GOOGLE_DESKTOP_CLIENT_ID") ?: "")
        environment("GOOGLE_API_KEY", System.getenv("GOOGLE_API_KEY") ?: "")
        environment("GOOGLE_GEMINI_API_KEY", System.getenv("GOOGLE_GEMINI_API_KEY") ?: "")
    }
}
