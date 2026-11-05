plugins {
    application
}

dependencies {
    implementation(project(":oq-core"))
    implementation(project(":oq-runtime"))
    implementation(project(":oq-stdlib"))
    implementation(project(":oq-compiler"))

    testImplementation(project(":oq-stdlib"))
}

application {
    mainClass.set("dev.oqthara.cli.Main")
    applicationName = "oq"
}

tasks.test {
    systemProperty("oq.root", rootProject.projectDir.absolutePath)
}

tasks.named("assemble") { dependsOn("standaloneJar") }

tasks.register<Jar>("standaloneJar") {
    group = "distribution"
    description = "Builds a self-contained oq.jar (interpreter + stdlib)."
    archiveFileName.set("oq.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "dev.oqthara.cli.Main"
        attributes["Implementation-Version"] = project.version.toString()
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}

val packageApp = tasks.register<Exec>("packageApp") {
    group = "distribution"
    description = "Builds a native launcher with jpackage (JDK 17+). Run per OS."
    dependsOn("standaloneJar")
    val outDir = layout.buildDirectory.dir("package").get().asFile
    val jar = layout.buildDirectory.file("libs/oq.jar").get().asFile
    doFirst {
        outDir.deleteRecursively()
        outDir.mkdirs()
    }
    commandLine(
        "${System.getProperty("java.home")}/bin/jpackage",
        "--type", "app-image",
        "--name", "oq",
        "--input", jar.parentFile.absolutePath,
        "--main-jar", jar.name,
        "--main-class", "dev.oqthara.cli.Main",
        "--app-version", "1.0.0",
        "--dest", outDir.absolutePath,
        "--java-options", "-Xmx512m",
    )
    isIgnoreExitValue = false
    doLast {
        val hint = when {
            System.getProperty("os.name").lowercase().contains("mac") ->
                "$outDir/oq.app/Contents/MacOS/oq"
            System.getProperty("os.name").lowercase().contains("win") ->
                "$outDir/oq/oq.exe"
            else -> "$outDir/oq/bin/oq"
        }
        println("native launcher: $hint")
    }
}
