plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("plugin.serialization")
    id("org.jmailen.kotlinter")
    id("org.graalvm.buildtools.native") version "0.9.25"
}

dependencyManagement {
    imports {
        mavenBom("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.14.0")
    }
}

// Create separate configurations for the two variants
val onnxRuntimeClasspath by configurations.creating {
    extendsFrom(configurations.runtimeClasspath.get())
}

dependencies {
    // Core dependencies - always included
    implementation(project(":open-responses-core"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    
    // ONNX dependency - only in the onnx variant
    onnxRuntimeClasspath(project(":open-responses-onnx"))
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

// Configure the default Spring Boot jar to be the basic version (core only)
tasks.bootJar {
    archiveClassifier.set("")
    archiveBaseName.set("openresponses")
    // This will use the standard runtimeClasspath which excludes ONNX
}

// Create a simple fat jar with ONNX support using the standard Jar task
tasks.register<Jar>("fatJarOnnx") {
    group = "build"
    description = "Creates a fat jar with ONNX support"
    
    archiveClassifier.set("onnx")
    archiveBaseName.set("openresponses")
    
    manifest {
        attributes["Main-Class"] = "ai.masaic.OpenResponsesApplicationKt"
        attributes["Implementation-Title"] = "OpenResponses ONNX"
        attributes["Implementation-Version"] = project.version
    }
    
    from(sourceSets.main.get().output)
    
    // Include dependencies from both configurations
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    from(onnxRuntimeClasspath.map { if (it.isDirectory) it else zipTree(it) })
    
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    
    // Enable zip64 for large archives
    isZip64 = true
    
    dependsOn(configurations.runtimeClasspath)
    dependsOn(project(":open-responses-onnx").tasks.jar)

}

// Create a task to build both variants
tasks.register("buildAll") {
    group = "build"
    description = "Builds both basic and ONNX variants"
    dependsOn(tasks.bootJar, tasks.named("fatJarOnnx"))
}

// Ensure JavaCompile tasks target the same JVM version for AOT compatibility
tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    options.release.set(21)
}

// Configure GraalVM native image build with Spring AOT support
graalvmNative {
    binaries {
        named("main") {
            imageName.set("openresponses-native")
            buildArgs.addAll(
                "--no-fallback",
                "-H:+StaticExecutableWithDynamicLibC",
                "-H:+AllowIncompleteClasspath"
            )
            javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21))
                    vendor.set(org.gradle.jvm.toolchain.JvmVendorSpec.GRAAL_VM)
                }
            )
        }
    }
}

