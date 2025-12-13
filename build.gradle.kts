import groovy.json.JsonSlurper
import java.net.URL

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

private val fallbackGameVersions = listOf(
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

private fun versionAtLeast(version: String, floor: List<Int>): Boolean {
    val parts = version.split(".").mapNotNull { it.toIntOrNull() }
    val maxSize = maxOf(parts.size, floor.size)
    for (i in 0 until maxSize) {
        val current = parts.getOrElse(i) { 0 }
        val min = floor.getOrElse(i) { 0 }
        if (current != min) return current > min
    }
    return true
}

private fun resolvedGameVersions(): List<String> {
    val override = System.getenv("MODRINTH_GAME_VERSIONS")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
    if (!override.isNullOrEmpty()) return override

    return try {
        val manifest = URL("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
            .openStream().use { JsonSlurper().parse(it) as Map<*, *> }
        val versions = manifest["versions"] as? List<*> ?: return fallbackGameVersions
        val floor = listOf(1, 19, 4)
        versions.asSequence()
            .mapNotNull { it as? Map<*, *> }
            .filter { it["type"] == "release" }
            .mapNotNull { it["id"] as? String }
            .filter { versionAtLeast(it, floor) }
            .toList()
            .ifEmpty { fallbackGameVersions }
    } catch (e: Exception) {
        logger.warn("Falling back to static MC versions for Modrinth: ${e.message}")
        fallbackGameVersions
    }
}

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
    gameVersions.set(resolvedGameVersions())
    loaders.set(listOf("velocity"))
    syncBodyFrom.set(rootProject.file("README.md"))
}
