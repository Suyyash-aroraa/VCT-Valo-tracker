plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

sourceSets["test"].resources.srcDir("../fixtures")

dependencies {
    api(libs.jsoup)
    // Android ships org.json; the JVM tools and tests bring their own copy.
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
}
