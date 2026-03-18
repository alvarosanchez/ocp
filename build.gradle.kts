plugins {
    alias(libs.plugins.micronaut.application)
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.test.logger)
    jacoco
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
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent {
            snapshotsOnly()
        }
    }
}

dependencies {
    annotationProcessor(libs.micronaut.inject.java)
    annotationProcessor(libs.picocli.codegen)
    annotationProcessor(libs.micronaut.serde.processor)

    implementation(libs.micronaut.picocli)
    implementation(libs.micronaut.serde.jackson)
    implementation(platform(libs.tamboui.bom))
    implementation(libs.tamboui.toolkit)
    implementation(libs.tamboui.panama.backend)

    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.micronaut.test.junit5)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.gitserver)
    testImplementation(libs.commons.codec)
    testImplementation(testFixtures(libs.tamboui.toolkit.test.fixtures))
    testImplementation(testFixtures(libs.tamboui.tui.test.fixtures))
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
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.13"
}

val jacocoTestReport by tasks.existing(JacocoReport::class) {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

val generateCoverageBadge by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Generates the README coverage badge from the JaCoCo XML report."
    dependsOn(jacocoTestReport)

    val jacocoXml = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")
    val outputFile = layout.projectDirectory.file("assets/badges/coverage.svg")

    inputs.files(jacocoXml).withPropertyName("jacocoXml").optional()
    outputs.file(outputFile)

    doLast {
        val reportFile = jacocoXml.get().asFile
        if (!reportFile.isFile) {
            throw GradleException("JaCoCo XML report not found at ${reportFile}. Run jacocoTestReport first.")
        }

        val documentBuilderFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val documentBuilder = documentBuilderFactory.newDocumentBuilder()
        val document = reportFile.inputStream().use(documentBuilder::parse)
        val root = document.documentElement
        val lineCounter = (0 until root.childNodes.length)
            .map { root.childNodes.item(it) }
            .filterIsInstance<org.w3c.dom.Element>()
            .firstOrNull { it.tagName == "counter" && it.getAttribute("type") == "LINE" }
            ?: throw GradleException("JaCoCo XML report did not contain a report-level LINE counter.")

        val missed = lineCounter.getAttribute("missed").toInt()
        val covered = lineCounter.getAttribute("covered").toInt()
        val total = missed + covered
        val percentage = if (total == 0) 0 else ((covered * 100.0) / total).toInt()
        val color = when {
            percentage >= 90 -> "#4c1"
            percentage >= 75 -> "#97ca00"
            percentage >= 60 -> "#dfb317"
            percentage >= 40 -> "#fe7d37"
            else -> "#e05d44"
        }
        val value = percentage.toString() + "%"
        val label = "coverage"
        val labelWidth = 74
        val valueWidth = 54
        val totalWidth = labelWidth + valueWidth
        val badge = """
            <svg xmlns="http://www.w3.org/2000/svg" width="${totalWidth}" height="20" role="img" aria-label="${label}: ${value}">
              <title>${label}: ${value}</title>
              <linearGradient id="s" x2="0" y2="100%">
                <stop offset="0" stop-color="#bbb" stop-opacity=".1"/>
                <stop offset="1" stop-opacity=".1"/>
              </linearGradient>
              <clipPath id="r">
                <rect width="${totalWidth}" height="20" rx="3" fill="#fff"/>
              </clipPath>
              <g clip-path="url(#r)">
                <rect width="${labelWidth}" height="20" fill="#555"/>
                <rect x="${labelWidth}" width="${valueWidth}" height="20" fill="${color}"/>
                <rect width="${totalWidth}" height="20" fill="url(#s)"/>
              </g>
              <g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" text-rendering="geometricPrecision" font-size="110">
                <text aria-hidden="true" x="380" y="150" fill="#010101" fill-opacity=".3" transform="scale(.1)">${label}</text>
                <text x="380" y="140" transform="scale(.1)" fill="#fff">${label}</text>
                <text aria-hidden="true" x="1000" y="150" fill="#010101" fill-opacity=".3" transform="scale(.1)">${value}</text>
                <text x="1000" y="140" transform="scale(.1)" fill="#fff">${value}</text>
              </g>
            </svg>
        """.trimIndent() + "\n"
        outputFile.asFile.parentFile.mkdirs()
        outputFile.asFile.writeText(badge)
    }
}

tasks.check {
    dependsOn(jacocoTestReport)
}

val generateVersionResource by tasks.registering {
    val outputFile = generatedResourcesDir.map { it.file("META-INF/ocp/version.txt") }
    val resolvedVersion = version.toString()

    outputs.file(outputFile)
    inputs.property("ocpVersion", resolvedVersion)

    doLast {
        val versionFile = outputFile.get().asFile
        versionFile.parentFile.mkdirs()
        versionFile.writeText(resolvedVersion)
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
            buildArgs.add("--enable-monitoring=jfr")
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
            buildArgs.add("-H:+UnlockExperimentalVMOptions")
            buildArgs.add("-H:+SharedArenaSupport")
            buildArgs.add("-H:IncludeResources=META-INF/ocp/version.txt|dev/tamboui/tui/bindings/.*\\.properties|splash-logo\\.txt")
        }
        named("test") {
            buildArgs.add("--initialize-at-build-time=org.junit.platform.commons.logging.LoggerFactory\$DelegatingLogger")
            buildArgs.add("--enable-monitoring=jfr")
            buildArgs.add("-H:+UnlockExperimentalVMOptions")
            buildArgs.add("-H:+SharedArenaSupport")
            buildArgs.add("-H:IncludeResources=dev/tamboui/tui/bindings/.*\\.properties")
            quickBuild.set(true)
        }
    }
}
