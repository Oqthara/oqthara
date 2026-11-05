dependencies {
    api(project(":oq-core"))
    api(project(":oq-runtime"))
    // The compiled backend asks the stdlib which builtins it can dispatch, so the compiler
    // and the interpreter share one list instead of drifting apart.
    implementation(project(":oq-stdlib"))
}

tasks.test {
    systemProperty("oq.root", rootProject.projectDir.absolutePath)
}
