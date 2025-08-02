plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("plugin.spring") version "2.1.0" apply false
    id("org.springframework.boot") version "3.4.3" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    kotlin("plugin.serialization") version "1.9.25" apply false
    id("org.jmailen.kotlinter") version "5.0.1" apply false
}

allprojects {
    group = "ai.masaic"
    version = "0.4.7"

    repositories {
        mavenCentral()
    }
}
