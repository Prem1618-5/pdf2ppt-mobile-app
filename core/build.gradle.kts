// Android-free domain code: runs and is tested on a plain JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) ; compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // desktop twin of pdfbox-android (same 2.0.27 API) to check text extraction and rendering on the JVM
    testImplementation(libs.pdfbox.desktop)
}

tasks.test {
    // fixtures and the pptx validator live in the copied handoff pack
    systemProperty("handoff.dir", rootProject.file("handoff").absolutePath)
    systemProperty("out.dir", layout.buildDirectory.dir("test-out").get().asFile.absolutePath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
