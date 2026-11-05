plugins {
    application
}

dependencies {
    implementation(project(":oq-core"))
    implementation(project(":oq-stdlib"))
}

application {
    mainClass.set("dev.oqthara.lsp.Main")
    applicationName = "oq-lsp"
}

tasks.register<Jar>("standaloneJar") {
    group = "distribution"
    description = "Builds a self-contained oq-lsp.jar for editors."
    archiveFileName.set("oq-lsp.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "dev.oqthara.lsp.Main"
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
