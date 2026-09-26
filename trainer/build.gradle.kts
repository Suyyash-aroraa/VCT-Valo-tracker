plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

application {
    mainClass.set("com.vcttracker.trainer.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    maxHeapSize = "2g"
}

sourceSets["test"].resources.srcDir("../fixtures")

dependencies {
    implementation(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.org.json)
    testImplementation(libs.junit)
}
