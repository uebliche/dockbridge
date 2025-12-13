plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.modrinth.minotaur") version "2.8.7"
    id("xyz.jpenilla.run-velocity") version "2.3.0"
}

group = "net.uebliche.dockbridge"

val pluginVersion: String = System.getenv("DOCKBRIDGE_VERSION")
    ?: System.getenv("MODRINTH_VERSION")
    ?: "2025-11-29"

version = pluginVersion
buildDir = file("/tmp/dockbridge-build")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")

    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.github.docker-java:docker-java-api:3.3.6")
    implementation("com.github.docker-java:docker-java-core:3.3.6")
    implementation("com.github.docker-java:docker-java-transport-zerodep:3.3.6")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("com.google.gson", "net.uebliche.dockbridge.libs.gson")
    relocate("com.github.dockerjava", "net.uebliche.dockbridge.libs.dockerjava")
    relocate("org.apache.hc", "net.uebliche.dockbridge.libs.hc")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.runVelocity {
    velocityVersion("3.4.0-SNAPSHOT")
    dependsOn(tasks.shadowJar)
}

modrinth {
    token.set(System.getenv("MODRINTH_TOKEN") ?: "")
    projectId.set("dockbridge")
    versionNumber.set(System.getenv("MODRINTH_VERSION") ?: pluginVersion)
    changelog.set(System.getenv("MODRINTH_CHANGELOG") ?: "")
    versionType.set("release")
    uploadFile.set(tasks.shadowJar.flatMap { it.archiveFile })
    gameVersions.set(
        listOf(
            "1.19.4",
            "1.20",
            "1.20.1",
            "1.20.2",
            "1.20.3",
            "1.20.4",
            "1.20.5",
            "1.20.6",
            "1.21",
            "1.21.1"
        )
    )
    loaders.set(listOf("velocity"))
    syncBodyFrom.set(rootProject.file("README.md"))
}
