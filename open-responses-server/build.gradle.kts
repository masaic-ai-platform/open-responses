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

dependencies {
    implementation(project(":open-responses-core"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    
    // ONNX module available for IDE development (compileOnly = not included in default JAR)
//    compileOnly(project(":open-responses-onnx"))
}

repositories {
    mavenCentral()
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

// Configure the default bootJar to be the core-only version
tasks.bootJar {
    archiveBaseName.set("openresponses")
    // Default bootJar will only include open-responses-core dependencies
}

// Create the ONNX variant bootJar
tasks.register<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJarOnnx") {
    group = "build"
    description = "Creates executable jar with ONNX support"
    
    archiveBaseName.set("openresponses")
    archiveClassifier.set("onnx")
    
    mainClass.set("ai.masaic.OpenResponsesApplicationKt")
    targetJavaVersion = JavaVersion.VERSION_21
    
    // Include the main source set
    from(sourceSets.main.get().output)
    
    // Include core dependencies
    classpath(configurations.runtimeClasspath.get())
    
    // Include ONNX module and its dependencies
    classpath(project(":open-responses-onnx").configurations.runtimeClasspath.get())
    classpath(project(":open-responses-onnx").tasks.jar.get().outputs)
    
    dependsOn(project(":open-responses-onnx").tasks.jar)
}

// Make both jars build by default
tasks.named("build") {
    dependsOn(tasks.bootJar, tasks.named("bootJarOnnx"))
}
