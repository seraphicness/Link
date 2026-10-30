plugins {
    `java-library`
    `maven-publish`
}
group = "io.linkmc"
version = "0.1.0-SNAPSHOT"
java { withSourcesJar(); withJavadocJar() }
publishing {
    publications.create<MavenPublication>("linkApi") { from(components["java"]) }
}
