import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.modrinth.minotaur") version "2.8.7"
    id("xyz.jpenilla.run-velocity") version "2.3.0"
}

group = "net.uebliche.dockbridge"

val buildDirOverride = file("/tmp/dockbridge-build")
buildDir = buildDirOverride

fun gitOutput(vararg args: String): String? {
    return try {
        val stdout = ByteArrayOutputStream()
        exec {
            commandLine("git", *args)
            standardOutput = stdout
            isIgnoreExitValue = true
        }
        stdout.toString().trim().ifBlank { null }
    } catch (_: Exception) {
        null
    }
}

fun computeReleaseVersion(datePart: String): String {
    val tags = gitOutput("tag", "--list", "${datePart}*")
        ?.lines()
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    if (tags.isEmpty()) return datePart

    val tagPattern = Regex("^${Regex.escape(datePart)}(?:-([A-Z]))?$")
    var maxIndex = -1
    for (tag in tags) {
        val match = tagPattern.matchEntire(tag) ?: continue
        val letter = match.groupValues.getOrNull(1)?.firstOrNull()
        val index = if (letter == null) 0 else (letter.code - 'A'.code)
        if (index > maxIndex) maxIndex = index
    }

    if (maxIndex < 0) return datePart

    val nextIndex = maxIndex + 1
    if (nextIndex >= 26) {
        throw GradleException("Too many releases for $datePart (max Z).")
    }
    val nextLetter = ('A'.code + nextIndex).toChar()
    return "$datePart-$nextLetter"
}

fun computePluginVersion(): String {
    val datePart = LocalDate.now(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
    val isReleaseBuild = System.getenv("DOCKBRIDGE_RELEASE")
        ?.equals("true", ignoreCase = true) == true
    if (isReleaseBuild) {
        return computeReleaseVersion(datePart)
    }

    val gitHash = gitOutput("rev-parse", "--short=8", "HEAD") ?: "nogit"
    return "$datePart-$gitHash"
}

val pluginVersion = computePluginVersion()
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
    versionNumber.set(pluginVersion)
    changelog.set(System.getenv("MODRINTH_CHANGELOG") ?: "")
    versionType.set("release")
    uploadFile.set(tasks.shadowJar.flatMap { it.archiveFile })
    gameVersions.set(resolvedGameVersions())
    loaders.set(listOf("velocity"))
    syncBodyFrom.set(
        providers.fileContents(rootProject.layout.projectDirectory.file("README.md")).asText
    )
}

tasks.register("printVersion") {
    doLast {
        println(project.version.toString())
    }
}
