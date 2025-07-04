plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "open-responses"
include("open-responses-core")
include("open-responses-onnx")
include("open-responses-server")
