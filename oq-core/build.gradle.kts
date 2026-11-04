dependencies {
    api(project(":oq-runtime"))
}

sourceSets {
    test {
        resources.srcDir("src/fuzz/resources")
    }
}
