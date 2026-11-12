plugins {
    base
    jacoco
}

allprojects {
    group = "dev.oqthara"
    version = "0.1.0-alpha.3"
    repositories {
        mavenCentral()
    }
}

// --- Fuzz targets: the one authoritative list ---------------------------------------
// Declared once for the whole build. The subprojects block plans the tasks from it and
// `printFuzzTargets` publishes it to CI metadata, so the two can never disagree.
data class FuzzTarget(val id: String, val className: String, val method: String)

val allFuzzTargets = listOf(
    FuzzTarget("LexerText", "dev.oqthara.core.LexerFuzzTest",
        "lexerNeverThrowsUnexpectedExceptions"),
    FuzzTarget("ParserText", "dev.oqthara.core.ParserFuzzTest",
        "parserNeverThrowsUnexpectedExceptions"),
    FuzzTarget("ParserNesting", "dev.oqthara.core.ParserFuzzTest",
        "deeplyNestedInputDoesNotOverflow"),
    FuzzTarget("ParserBlockNesting", "dev.oqthara.core.ParserFuzzTest",
        "deeplyNestedBlocksDoNotOverflow"),
    FuzzTarget("Pipeline", "dev.oqthara.compiler.PipelineFuzzTest",
        "fullPipelineNeverThrowsUnexpectedExceptions"),
    FuzzTarget("JsonParse", "dev.oqthara.stdlib.JsonFuzzTest",
        "jsonParseNeverThrowsUnexpectedExceptions"),
    FuzzTarget("JsonNesting", "dev.oqthara.stdlib.JsonFuzzTest",
        "deeplyNestedJsonDoesNotOverflow"),
    FuzzTarget("StdlibBuiltins", "dev.oqthara.stdlib.StdlibFuzzTest",
        "pureBuiltinsNeverThrowUnexpectedExceptions"),
)

tasks.register("printFuzzTargets") {
    group = "verification"
    description = "Prints every Jazzer fuzz target id, one per line, in declaration order."
    doLast {
        allFuzzTargets.forEach { println(it.id) }
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
        // The build cache is shared across the whole matrix, and Gradle's default key for a
        // Test task is the classpath, the inputs, and the JVM - not the operating system. A
        // green Linux run is therefore replayed as a green macOS run, which defeats the point
        // of running the suite there: the tests that differ by platform (path separators,
        // line endings, executable bits, process launch) are exactly the ones each leg exists
        // to catch. Declaring the OS as an input gives each platform its own cache entries
        // while still sharing within a platform across JDK versions where that is sound.
        //
        // Observed: the first macOS run executed every test task, and every later macOS run
        // reported `FROM-CACHE` for six of seven, including oq-core and oq-stdlib.
        inputs.property("operatingSystem", System.getProperty("os.name"))
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

    // --- Coverage-guided fuzzing (Jazzer) -------------------------------------------
    // Jazzer lives only in fuzz/test configurations, never in production runtime.
    val javaExt = extensions.getByType<JavaPluginExtension>()
    val mainSource = javaExt.sourceSets.getByName("main")
    val fuzzSourceSet = javaExt.sourceSets.create("fuzz") {
        compileClasspath += mainSource.output
        runtimeClasspath += mainSource.output
    }
    val implementationConfig = configurations.getByName("implementation")
    configurations.getByName("fuzzImplementation").extendsFrom(implementationConfig)
    configurations.getByName("fuzzImplementation").extendsFrom(
        configurations.getByName("testImplementation"))
    configurations.getByName("fuzzRuntimeOnly").extendsFrom(
        configurations.getByName("testRuntimeOnly"))

    dependencies {
        "fuzzImplementation"("com.code-intelligence:jazzer-junit:0.30.0")
        "fuzzImplementation"(platform("org.junit:junit-bom:6.1.3"))
        "fuzzImplementation"("org.junit.jupiter:junit-jupiter")
        "fuzzRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // Jazzer's JUnit integration runs exactly ONE @FuzzTest method per JVM invocation.
    // Running a class with several targets silently skips the rest, so every target is
    // registered as its own task and the aggregate task depends on all of them.
    // The target list itself is declared once at the top of this script.
    //
    // A target can only run in the module whose fuzz source set contains its class.
    val packageOfTarget = mapOf(
        "dev.oqthara.core." to "oq-core",
        "dev.oqthara.compiler." to "oq-compiler",
        "dev.oqthara.stdlib." to "oq-stdlib",
    )
    val fuzzTargets = allFuzzTargets.filter { target ->
        packageOfTarget.entries.any { (prefix, moduleName) ->
            target.className.startsWith(prefix) && project.name == moduleName
        }
    }

    val fuzzSource = javaExt.sourceSets.getByName("fuzz")
    val hasFuzz = projectDir.resolve("src/fuzz").isDirectory
    if (hasFuzz) {
        val targetTasks = fuzzTargets.map { target ->
            tasks.register<Test>("fuzz${target.id}") {
                group = "verification"
                description = "Jazzer target ${target.className}#${target.method}"
                testClassesDirs = fuzzSource.output.classesDirs
                classpath = fuzzSource.runtimeClasspath
                useJUnitPlatform()
                filter { includeTestsMatching("${target.className}.${target.method}") }
                systemProperty("jazzer.coverage.disable", "false")
                maxHeapSize = "2g"
                jvmArgs("-Dfile.encoding=UTF-8")
                outputs.upToDateWhen { false }
                testLogging { events("passed", "failed"); showStandardStreams = true }
            }
        }
        tasks.register("fuzzTest") {
            group = "verification"
            description = "Runs every Jazzer fuzz target (one JVM per target)."
            dependsOn(targetTasks)
        }
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}

val jacocoAggregate = tasks.register<JacocoReport>("jacocoAggregate") {
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

val coverageGate = tasks.register("coverageGate") {
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
