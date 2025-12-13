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

private fun resolvedGameVersions(): List<String> {
    val override = System.getenv("MODRINTH_GAME_VERSIONS")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
    if (!override.isNullOrEmpty()) return override

    return try {
        val connection = URL("https://api.modrinth.com/v2/tag/game_version").openConnection()
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.getInputStream().use { stream ->
            val parsed = JsonSlurper().parse(stream) as? List<*> ?: return emptyList()
            parsed.mapNotNull { (it as? Map<*, *>)?.get("version") as? String }
                .filter { it.isNotBlank() }
                .distinct()
        }
            .ifEmpty { emptyList() }
    } catch (e: Exception) {
        logger.warn("Failed to resolve MC versions from Modrinth: ${e.message}")
        emptyList()
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
    syncBodyFrom.set(
        providers.fileContents(rootProject.layout.projectDirectory.file("README.md")).asText
    )
}
