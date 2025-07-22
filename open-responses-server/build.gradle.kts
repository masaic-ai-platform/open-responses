plugins {
    kotlin("jvm")                          // no version here – let Spring Boot manage it
    kotlin("plugin.spring")
    id("org.springframework.boot")         // no version – same
    id("io.spring.dependency-management")
    kotlin("plugin.serialization")
    id("org.jmailen.kotlinter")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
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

// your ONNX flag
val enableOnnx: Boolean = (project.findProperty("enableOnnx") as String?)?.toBoolean() ?: false

dependencies {
    implementation(project(":open-responses-core"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // ensure Kotlin stdlib is on the runtime classpath
    implementation(kotlin("stdlib-jdk8"))

    // pull in ONNX _only_ when flag is true
    if (enableOnnx) {
        implementation(project(":open-responses-onnx"))
    }

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// single bootJar, but add “onnx” classifier when flag is true
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    // always set your app’s main class
    mainClass.set("ai.masaic.OpenResponsesApplicationKt")

    if (enableOnnx) {
        // make the “onnx” build produce openresponses-onnx-<version>.jar
        archiveBaseName.set("openresponses-onnx")
        archiveClassifier.set("")           // clear classifier so it doesn't end up as -onnx twice
    } else {
        // core-only remains openresponses-<version>.jar
        archiveBaseName.set("openresponses")
        archiveClassifier.set("")
    }
}

tasks.withType<org.springframework.boot.gradle.tasks.bundling.BootJar> {
    manifest {
        attributes["Implementation-Version"] = project.version
    }
}

springBoot {
    buildInfo()
}
