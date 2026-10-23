plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.papertoslides"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.papertoslides"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // real phones only (keeps the APK small); add "x86_64" for an emulator build
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            if (providers.gradleProperty("withX86").isPresent) abiFilters += "x86_64" // CI emulator
        }
    }

    signingConfigs {
        getByName("debug") {
            // public debug key, committed on purpose (see DEBUG_KEYSTORE_README.md)
            storeFile = file("papertoslides-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module",
            // post-quantum tables from BouncyCastle (pulled in by PdfBox) are never used: -9 MB
            "org/bouncycastle/pqc/**")
    }
}

// Self-test papers (Settings -> Self-test) are bundled from the handoff pack, not duplicated in the repo
abstract class FixtureAssets : DefaultTask() {
    @get:InputDirectory abstract val source: DirectoryProperty
    @get:InputDirectory abstract val extra: DirectoryProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction fun copy() {
        val out = output.get().asFile
        out.deleteRecursively()
        source.get().asFile.copyRecursively(File(out, "fixtures"))
        File(out, "fixtures/lines").deleteRecursively()
        // edge cases for the text / template / dark-theme self-test
        for (f in listOf("edge_cases.pdf", "hindi_bilingual.pdf", "edge_expected.json")) {
            extra.get().asFile.resolve(f).copyTo(File(out, "fixtures/$f"), overwrite = true)
        }
    }
}
val fixtureAssets = tasks.register<FixtureAssets>("fixtureAssets") {
    source.set(rootProject.layout.projectDirectory.dir("handoff/fixtures"))
    extra.set(rootProject.layout.projectDirectory.dir("testdata"))
}
androidComponents {
    onVariants { it.sources.assets?.addGeneratedSourceDirectory(fixtureAssets, FixtureAssets::output) }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

base { archivesName.set("PaperToSlides-v${android.defaultConfig.versionName}") }

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.work.runtime)
    implementation(libs.core.ktx)
    implementation(libs.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.text.devanagari)
    implementation(libs.pdfbox.android) {
        // certificate (PKIX) support is only for certificate-encrypted / signed PDFs, which PdfRenderer cannot open
        // anyway; dropping it also removes a trust-all X509 manager from the APK
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcutil-jdk15to18")
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
