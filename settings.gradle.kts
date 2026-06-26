pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "net.uebliche.mcmeta") {
                val requestedVersion = requested.version
                    ?: providers.gradleProperty("mcmetaPluginVersion").orNull
                    ?: providers.environmentVariable("MCMETA_PLUGIN_VERSION").orNull
                    ?: "0.1.0"
                useModule("net.uebliche:mcmeta-gradle:${requestedVersion}")
            }
            if (requested.id.id in setOf("fabric-loom", "net.fabricmc.fabric-loom", "net.fabricmc.fabric-loom-remap")) {
                val requestedVersion = requested.version
                    ?: if (gradle.extra.has("fabricLoomVersion")) gradle.extra["fabricLoomVersion"]?.toString() else null
                if (!requestedVersion.isNullOrBlank()) {
                    useModule("net.fabricmc:fabric-loom:${requestedVersion}")
                }
            }
        }
    }
    val override = providers.gradleProperty("mcmetaPluginPath").orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: providers.environmentVariable("MCMETA_PLUGIN_PATH").orNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val mcmetaPluginDir = if (override != null) {
        java.io.File(override).let { if (it.isAbsolute) it else java.io.File(settingsDir, override) }
    } else {
        listOf(
            java.io.File(settingsDir, "../../tools/mcmeta-gradle/gradle-plugin"),
            java.io.File(settingsDir, "../../web/mcmeta/gradle-plugin"),
            java.io.File(settingsDir, "../../../mcmeta/gradle-plugin"),
        ).firstOrNull { it.exists() }
    }
    val mcmetaPluginAvailable = mcmetaPluginDir != null && mcmetaPluginDir.exists()
    if (mcmetaPluginAvailable) {
        includeBuild(mcmetaPluginDir.absolutePath)
    }
    gradle.extra["mcmetaPluginAvailable"] = mcmetaPluginAvailable
    gradle.extra["mcmetaPluginDir"] = mcmetaPluginDir
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "DockBridge"
