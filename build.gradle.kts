import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        // Stable AGP pairing that avoids the Configuration Mutate / Lock bug
        classpath("com.android.tools.build:gradle:8.1.1") 
        
        // Pinning the raw 10-character commit hash forces a clean, consistent POM generation on JitPack
        classpath("com.github.recloudstream:gradle:81b1d424d2")
        
        // Upgraded Kotlin compiler plugin to properly process newer cloudstream.jar metadata structures
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    }
}

subprojects {
    // Top-level compilation rules matching the modern plugin lifecycle
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xannotation-default-target=param-property",
                "-Xskip-metadata-version-check" // Bypasses metadata version drift validation blocks
            )
        }
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) {
    extensions.getByName<LibraryExtension>("android").apply {
        project.extensions.findByType(JavaPluginExtension::class.java)?.apply {
            // Force the system onto a standard Java 17 toolchain setup
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
        }
        configuration()
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android") // Explicitly forces the evaluation of your .kt extension files
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/ChocoCooper/cloudstream-repo")
        authors = listOf("ChocoCooper")
    }

    android {
        // Automatically isolates namespaces safely based on module sub-folder names
        namespace = "com.chococooper.${project.name.lowercase()}"
        compileSdk = 36

        defaultConfig {
            minSdk = 21
            targetSdk = 36 // Placed correctly inside defaultConfig for AGP 8+ compliance
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-Xannotation-default-target=param-property",
                    "-Xskip-metadata-version-check" // Assures local module compilers parse the stubs seamlessly
                )
            }
        }
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // Standard extension dependency graph configurations
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.18")
        implementation("org.jsoup:jsoup:1.22.2")
        implementation("androidx.annotation:annotation:1.10.0")
        
        // Strict Jackson mappings matching standard extension frameworks
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.13.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        
        // Script parsing utility libraries
        implementation("org.mozilla:rhino:1.8.1")
        implementation("me.xdrop:fuzzywuzzy:1.4.0")
        implementation("com.google.code.gson:gson:2.14.0")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        
        // Stable BouncyCastle version that avoids modern JDK bytecode major version extraction crashes
        implementation("org.bouncycastle:bcpkix-jdk15to18:1.77")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
