plugins {
    java
    id("com.gradleup.shadow")
}
group = "io.linkmc"
version = "0.1.0-SNAPSHOT"
repositories { maven("https://repo.papermc.io/repository/maven-public/") }
dependencies {
    implementation(project(":link-core")) {
        exclude(group = "io.netty")
    }
    compileOnly("com.velocitypowered:velocity-api:4.3.1")
    annotationProcessor("com.velocitypowered:velocity-api:4.3.1")
}
tasks.shadowJar {
    archiveBaseName.set("link-velocity")
    archiveClassifier.set("")
    mergeServiceFiles()
    relocate("io.lettuce", "io.linkmc.velocity.internal.lettuce")
    relocate("reactor", "io.linkmc.velocity.internal.reactor")
    relocate("org.reactivestreams", "io.linkmc.velocity.internal.reactivestreams")
    relocate("com.zaxxer.hikari", "io.linkmc.velocity.internal.hikari")
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
tasks.build { dependsOn(tasks.shadowJar) }
tasks.jar { archiveClassifier.set("plain") }
