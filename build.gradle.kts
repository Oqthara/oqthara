plugins {
    base
    jacoco
}

allprojects {
    group = "dev.oqthara"
    version = "0.1.0-alpha.1"
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:all")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Deterministic text handling: the suite compares generated output byte for byte,
        // so the test JVM must not depend on the platform default charset.
        systemProperty("file.encoding", "UTF-8")
        jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8")
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}

val jacocoAggregate by tasks.registering(JacocoReport::class) {
    group = "verification"
    description = "Aggregated coverage across all modules (conformance runs count)."
    val projects = subprojects
    dependsOn(projects.map { it.tasks.named("test") })
    executionData.setFrom(
        projects.flatMap { p ->
            p.tasks.withType<Test>().map { it.extensions.getByType<JacocoTaskExtension>().destinationFile!! }
        }
    )
    classDirectories.setFrom(
        projects.flatMap { p ->
            p.extensions.getByType<SourceSetContainer>().getByName("main").output.classesDirs
        }
    )
    sourceDirectories.setFrom(
        projects.flatMap { p ->
            p.extensions.getByType<SourceSetContainer>().getByName("main").allSource.srcDirs
        }
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

val coverageGate by tasks.registering {
    group = "verification"
    description = "Fails when oq-core line coverage drops below 80%."
    dependsOn(jacocoAggregate)
    val report = layout.buildDirectory.file("reports/jacoco/jacocoAggregate/jacocoAggregate.xml")
    doLast {
        val file = report.get().asFile
        if (!file.exists()) throw GradleException("coverage report missing: ${file.path}")
        val text = file.readText()
        val pkg = Regex("""<package name="dev/oqthara/core">(.*?)</package>""", RegexOption.DOT_MATCHES_ALL)
            .find(text) ?: throw GradleException("dev/oqthara/core not found in coverage report")
        val line = Regex("""<counter type="LINE" missed="(\d+)" covered="(\d+)"/>""")
            .findAll(pkg.groupValues[1]).lastOrNull() ?: throw GradleException("LINE counter missing")
        val missed = line.groupValues[1].toInt()
        val covered = line.groupValues[2].toInt()
        val pct = covered * 100.0 / (missed + covered)
        if (pct < 80.0) throw GradleException("oq-core line coverage is %.1f%%, below the 80%% gate".format(pct))
        println("oq-core line coverage: %.1f%% (gate 80%%)".format(pct))
    }
}

tasks.named("check") { dependsOn(coverageGate) }
