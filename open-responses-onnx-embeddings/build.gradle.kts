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
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2:1.0.0-beta2")
}

repositories {
    mavenCentral()
}

// Disable bootJar for library module
tasks.getByName<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}

tasks.getByName<Jar>("jar") {
    enabled = true
}
