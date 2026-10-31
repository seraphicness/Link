plugins { `java-library` }
dependencies {
    api(project(":link-api"))
    runtimeOnly("org.postgresql:postgresql:42.7.5")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.3")
    implementation(libs.lettuce)
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("software.amazon.awssdk:auth:2.31.54")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
tasks.register<JavaExec>("publishTemplate") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.linkmc.core.TemplateAdmin")
    if (project.hasProperty("templateFile")) {
        args(providers.gradleProperty("templateName").get(), providers.gradleProperty("templateVersion").get(), providers.gradleProperty("templateFile").get())
    }
}
