dependencies {
    api(project(":oq-core"))
    api(project(":oq-runtime"))
}

tasks.test {
    systemProperty("oq.root", rootProject.projectDir.absolutePath)
}
