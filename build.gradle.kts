val koog_version: String by project
val kotlin_version: String by project
val logback_version: String by project
val aws_sdk_version: String by project

plugins {
    kotlin("jvm") version "2.3.10"
    id("io.ktor.plugin") version "3.2.2"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10"
}

group = "ai.jetbrains"
version = "0.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "io.ktor.server.cio.EngineMain"
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-sse")
    implementation("ai.koog:koog-ktor:$koog_version")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-cio")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-server-config-yaml")

    implementation("aws.sdk.kotlin:bedrockruntime-jvm:$aws_sdk_version")
    implementation("aws.sdk.kotlin:bedrockagentcore-jvm:$aws_sdk_version")
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    testImplementation("io.mockk:mockk:1.13.12")
}
