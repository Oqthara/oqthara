// JMH benchmarks for the interpreter. This module is deliberately NOT part of
// `check`/`build`: benchmarks take minutes and must never gate a build.
val jmhVersion = "1.37"
val jmhSourceSet = sourceSets.create("jmh") {
    // Benchmarks use the shared Workloads/Runner harness in src/main.
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations.getByName("jmhImplementation")
    .extendsFrom(configurations.getByName("implementation"))
configurations.getByName("jmhRuntimeOnly")
    .extendsFrom(configurations.getByName("runtimeOnly"))

dependencies {
    implementation(project(":oq-core"))
    implementation(project(":oq-runtime"))
    implementation(project(":oq-stdlib"))

    "jmhImplementation"(project(":oq-core"))
    "jmhImplementation"(project(":oq-runtime"))
    "jmhImplementation"(project(":oq-stdlib"))
    "jmhImplementation"("org.openjdk.jmh:jmh-core:$jmhVersion")
    // The annotation processor is what turns @Benchmark methods into the
    // generated runner; without it JMH fails at runtime with "no benchmarks".
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
}

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs the interpreter JMH benchmarks. Pass args via -PjmhArgs=\"...\"."
    classpath = jmhSourceSet.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    // A benchmark is a measurement, never a cached artifact.
    outputs.upToDateWhen { false }
    val extraArgs: List<String> = (findProperty("jmhArgs") as String?)
        ?.split(" ")
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    args(extraArgs)
}
