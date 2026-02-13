plugins {
    alias(libs.plugins.micronaut.application)
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.test.logger)
}

val defaultVersion = "0.1.0"
val releaseVersion = providers.gradleProperty("releaseVersion")
    .orElse(providers.environmentVariable("RELEASE_VERSION"))
    .orNull

version = releaseVersion?.removePrefix("v") ?: defaultVersion
group = "com.github.alvarosanchez"

val generatedResourcesDir = layout.buildDirectory.dir("generated/resources/main")

val javaVersion = 25
val micronautPlatformVersion = libs.versions.micronaut.platform.get()

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    annotationProcessor(libs.micronaut.inject.java)
    annotationProcessor(libs.picocli.codegen)
    annotationProcessor(libs.micronaut.serde.processor)

    implementation(libs.micronaut.picocli)
    implementation(libs.micronaut.serde.jackson)
    implementation(libs.clique)
    implementation(libs.clique.themes)

    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.micronaut.test.junit5)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.gitserver)
    testImplementation(libs.commons.codec)
}

application {
    mainClass.set("com.github.alvarosanchez.ocp.command.OcpCommand")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

tasks.test {
    useJUnitPlatform()
}

val generateVersionResource by tasks.registering {
    val outputFile = generatedResourcesDir.map { it.file("META-INF/ocp/version.txt") }

    outputs.file(outputFile)

    doLast {
        val versionFile = outputFile.get().asFile
        versionFile.parentFile.mkdirs()
        versionFile.writeText(project.version.toString())
    }
}

tasks.processResources {
    dependsOn(generateVersionResource)
    from(generatedResourcesDir)
}

micronaut {
    version(micronautPlatformVersion)
    runtime("none")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.github.alvarosanchez.ocp.**")
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("ocp")
            mainClass.set("com.github.alvarosanchez.ocp.command.OcpCommand")
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:IncludeResources=META-INF/ocp/version.txt")
            quickBuild.set(true)
        }
        named("test") {
            buildArgs.add("--initialize-at-build-time=org.junit.platform.commons.logging.LoggerFactory\$DelegatingLogger")
            quickBuild.set(true)
        }
    }
}
