dependencies {
    api(project(":oq-core"))
    api(project(":oq-runtime"))

    // The only external runtime dependency in the project. See ADR-0040 D1.
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    // sqlite-jdbc logs through slf4j and otherwise prints three warnings to stderr
    // on first use, which would corrupt program output.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")
}
