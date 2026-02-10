plugins {
    id("io.micronaut.application") version "4.6.2"
    id("org.graalvm.buildtools.native") version "0.11.1"
}

version = "0.1.0"
group = "com.github.alvarosanchez"

repositories {
    mavenCentral()
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-inject-java")
    annotationProcessor("info.picocli:picocli-codegen")

    implementation("io.micronaut.picocli:micronaut-picocli")

    runtimeOnly("ch.qos.logback:logback-classic")

    testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
    mainClass.set("com.github.alvarosanchez.ocp.Application")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.test {
    useJUnitPlatform()
}

micronaut {
    version("4.10.7")
    runtime("none")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.github.alvarosanchez.ocp.*")
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("ocp")
            mainClass.set("com.github.alvarosanchez.ocp.Application")
            buildArgs.add("--no-fallback")
        }
    }
}
