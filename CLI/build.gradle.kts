plugins {
    java
    application
    checkstyle
}

group = "org.orb"
version = "1.0-SNAPSHOT"

checkstyle {
    toolVersion = "10.17.0"
    configFile = file("$rootDir/../checkstyle.xml")
}

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
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.orb.cli.OrbCLI")
    applicationName = "orb"
}