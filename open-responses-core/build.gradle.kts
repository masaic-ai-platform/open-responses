plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("plugin.serialization")
    id("org.jmailen.kotlinter")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencyManagement {
    imports {
        mavenBom("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.14.0")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
//    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.module:jackson-module-jsonSchema")
    implementation("io.pebbletemplates:pebble:3.1.5")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")
    implementation("jakarta.validation:jakarta.validation-api:3.0.2")
    implementation("com.openai:openai-java:2.2.0") {
        exclude(group = "io.grpc", module = "grpc-netty-shaded") // -18M
        exclude(group = "org.bouncycastle") // -17M if using JVM crypto
    }
    implementation("dev.langchain4j:langchain4j-mcp:1.0.0-beta2")
    implementation("dev.langchain4j:langchain4j:1.0.0-beta2")
    implementation("dev.langchain4j:langchain4j-open-ai-official:1.0.0-beta2")
    implementation("dev.langchain4j:langchain4j-qdrant:1.0.0-beta2") {
        exclude(group="io.grpc", module="grpc-netty-shaded")
    }

    implementation("io.grpc:grpc-netty:1.65.1")
    implementation("dev.langchain4j:langchain4j-open-ai:1.0.0-beta2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.knuddels:jtokkit:1.1.0")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-tracing-bridge-otel:1.4.4")
    api("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")
    implementation("io.micrometer:micrometer-core")
    implementation("io.micrometer:micrometer-observation")
    implementation("org.apache.tika:tika-core:3.1.0")
    implementation("org.apache.tika:tika-parsers-standard-package:3.1.0")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
    testImplementation("io.mockk:mockk:1.13.17")
    implementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.testcontainers:mongodb:1.19.1")
    testImplementation("org.testcontainers:junit-jupiter:1.19.1")
    implementation("org.apache.lucene:lucene-core:9.9.0")
    implementation("org.apache.lucene:lucene-analysis-common:9.9.0")
    implementation("org.apache.lucene:lucene-queryparser:9.9.0")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
    testLogging {
        events("PASSED", "SKIPPED", "FAILED")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

// Disable bootJar for library module - only the server module should create executable JARs
tasks.getByName<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}

// Enable regular jar for library usage
tasks.getByName<Jar>("jar") {
    enabled = true
}
