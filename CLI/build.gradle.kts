plugins {
    java
    application
}

group = "org.orb"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.eclipse.org/content/groups/releases/")

}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("info.picocli:picocli:4.7.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.4.0.202509020913-r")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
    // Added for indexing logic migration
    implementation("io.github.bonede:tree-sitter:0.26.6")
    implementation("io.github.bonede:tree-sitter-java:0.23.5")
    implementation("org.neo4j.driver:neo4j-java-driver:5.19.0")
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.orb.cli.OrbCLI")
    applicationName = "orb"
}
