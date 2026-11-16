plugins {
    kotlin("jvm")
}

// 2024.3 (sinceBuild 243) runs on Java 21. verifyPluginProjectConfiguration
// rejects a plugin bytecode target older than the platform requirement.
kotlin {
    jvmToolchain(21)
}

base {
    archivesName = "requesthandoff-core"
}

dependencies {
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.networknt:json-schema-validator:1.5.6")
}

tasks.test {
    useJUnitPlatform()
}
