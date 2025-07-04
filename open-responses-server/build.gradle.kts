plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("plugin.serialization")
    id("org.jmailen.kotlinter")
}

dependencies {
    implementation(project(":open-responses-core"))
    runtimeOnly(project(":open-responses-onnx-embeddings"))
}

repositories {
    mavenCentral()
}
