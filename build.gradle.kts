import groovy.json.JsonSlurper
import java.net.URL

plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("xyz.jpenilla.run-velocity") version "2.3.0"
}

group = "net.uebliche.dockbridge"

val buildDirOverride = file("/tmp/dockbridge-build")
buildDir = buildDirOverride

val pluginVersion = project.findProperty("tag")?.toString()?.trim().takeIf { !it.isNullOrBlank() } ?: "dev"
version = pluginVersion

val generatedBuildConstantsDir = layout.buildDirectory.dir("generated/sources/buildConstants/java")

val generateBuildConstants by tasks.registering {
    outputs.dir(generatedBuildConstantsDir)
    doLast {
        val targetDir = generatedBuildConstantsDir.get().asFile
            .resolve("net/uebliche/dockbridge")
        targetDir.mkdirs()

        val escapedVersion = pluginVersion
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        val outputFile = targetDir.resolve("BuildConstants.java")
        outputFile.writeText(
            """
            package net.uebliche.dockbridge;

            public final class BuildConstants {
                public static final String VERSION = "$escapedVersion";

                private BuildConstants() {
                }
            }
            """.trimIndent() + "\n"
        )
    }
}

sourceSets {
    main {
        java.srcDir(generatedBuildConstantsDir)
    }
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateBuildConstants)
}

tasks.withType<Jar>().configureEach {
    if (name == "sourcesJar") {
        dependsOn(generateBuildConstants)
    }
}

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
    implementation("com.github.docker-java:docker-java-transport:3.3.6")
    implementation("com.github.docker-java:docker-java-transport-zerodep:3.3.6")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar {
    archiveClassifier.set("plain")
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
    pluginJars.from(tasks.shadowJar.flatMap { it.archiveFile })
    dependsOn(tasks.shadowJar)
}


tasks.register("printVersion") {
    doLast {
        println(project.version.toString())
    }
}
