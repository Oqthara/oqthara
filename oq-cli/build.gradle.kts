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
