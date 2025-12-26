package net.uebliche.dockbridge;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/**
 * Lightweight configuration wrapper. It keeps reading minimal so it can be
 * replaced by a richer parser later without touching callers.
 */
public final class DockBridgeConfig {

    private static final String CONFIG_FILE_NAME = "dockbridge.conf";

    private final String dockerEndpoint;
    private final int dockerPollIntervalSeconds;
    private final String proxyGroup;
    private final boolean healthEnablePing;
    private final int healthPingIntervalSeconds;
    private final int healthMaxFailures;
    private final String autoRegisterLabelKey;
    private final String autoRegisterLabelValue;
    private final String autoRegisterNameLabel;
    private final String autoRegisterPortLabel;
    private final String duplicateStrategy;
    private final boolean logScan;
    private final boolean logMatches;
    private final boolean logSummary;
    private final boolean logSummaryWhenUnchanged;
    private final boolean logRegistered;
    private final boolean logUpdated;
    private final boolean logUnregistered;

    private DockBridgeConfig(
            String dockerEndpoint,
            int dockerPollIntervalSeconds,
            String proxyGroup,
            boolean healthEnablePing,
            int healthPingIntervalSeconds,
            int healthMaxFailures,
            String autoRegisterLabelKey,
            String autoRegisterLabelValue,
            String autoRegisterNameLabel,
            String autoRegisterPortLabel,
            String duplicateStrategy,
            boolean logScan,
            boolean logMatches,
            boolean logSummary,
            boolean logSummaryWhenUnchanged,
            boolean logRegistered,
            boolean logUpdated,
            boolean logUnregistered
    ) {
        this.dockerEndpoint = Objects.requireNonNull(dockerEndpoint, "dockerEndpoint");
        this.dockerPollIntervalSeconds = dockerPollIntervalSeconds;
        this.proxyGroup = Objects.requireNonNull(proxyGroup, "proxyGroup");
        this.healthEnablePing = healthEnablePing;
        this.healthPingIntervalSeconds = healthPingIntervalSeconds;
        this.healthMaxFailures = healthMaxFailures;
        this.autoRegisterLabelKey = Objects.requireNonNull(autoRegisterLabelKey, "autoRegisterLabelKey");
        this.autoRegisterLabelValue = Objects.requireNonNull(autoRegisterLabelValue, "autoRegisterLabelValue");
        this.autoRegisterNameLabel = Objects.requireNonNull(autoRegisterNameLabel, "autoRegisterNameLabel");
        this.autoRegisterPortLabel = Objects.requireNonNull(autoRegisterPortLabel, "autoRegisterPortLabel");
        this.duplicateStrategy = Objects.requireNonNull(duplicateStrategy, "duplicateStrategy");
        this.logScan = logScan;
        this.logMatches = logMatches;
        this.logSummary = logSummary;
        this.logSummaryWhenUnchanged = logSummaryWhenUnchanged;
        this.logRegistered = logRegistered;
        this.logUpdated = logUpdated;
        this.logUnregistered = logUnregistered;
    }

    public static DockBridgeConfig load(Path dataDirectory, Logger logger) {
        Path configPath = dataDirectory.resolve(CONFIG_FILE_NAME);

        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            logger.warn("Could not create data directory {}: {}", dataDirectory, e.getMessage());
        }

        if (Files.notExists(configPath)) {
            copyDefaultConfig(configPath, logger);
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configPath)) {
            properties.load(input);
        } catch (IOException e) {
            logger.warn("Failed to read config {}, using defaults where needed. Error: {}", configPath, e.getMessage());
        }

        return fromProperties(properties, logger);
    }

    private static void copyDefaultConfig(Path target, Logger logger) {
        try (InputStream defaultConfig = DockBridgeConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE_NAME)) {
            if (defaultConfig == null) {
                logger.warn("Default configuration {} not found on classpath.", CONFIG_FILE_NAME);
                return;
            }
            Files.copy(defaultConfig, target);
            logger.info("Created default configuration at {}", target);
        } catch (IOException e) {
            logger.warn("Could not create default configuration at {}: {}", target, e.getMessage());
        }
    }

    private static DockBridgeConfig fromProperties(Properties properties, Logger logger) {
        String dockerEndpoint = properties.getProperty("docker.endpoint", "unix:///var/run/docker.sock");
        int dockerPoll = readInt(properties, "docker.poll_interval_seconds", 30, logger);
        String proxyGroup = properties.getProperty("filters.proxy_group", "default");
        boolean enablePing = readBoolean(properties, "health.enable_ping", true);
        int pingInterval = readInt(properties, "health.ping_interval_seconds", 10, logger);
        int maxFailures = readInt(properties, "health.max_failures", 3, logger);
        String autoLabelKey = properties.getProperty("docker.autoregister.label_key", "net.uebliche.dockbridge.autoregister");
        String autoLabelValue = properties.getProperty("docker.autoregister.label_value", "true");
        String autoNameLabel = properties.getProperty("docker.autoregister.name_label", "net.uebliche.dockbridge.server_name");
        String autoPortLabel = properties.getProperty("docker.autoregister.port_label", "net.uebliche.dockbridge.server_port");
        String duplicateStrategy = properties.getProperty("docker.autoregister.duplicate_strategy", "suffix");
        boolean logScan = readBoolean(properties, "logging.scan", false);
        boolean logMatches = readBoolean(properties, "logging.matches", false);
        boolean logSummary = readBoolean(properties, "logging.summary", true);
        boolean logSummaryWhenUnchanged = readBoolean(properties, "logging.summary_when_unchanged", false);
        boolean logRegistered = readBoolean(properties, "logging.registered", true);
        boolean logUpdated = readBoolean(properties, "logging.updated", true);
        boolean logUnregistered = readBoolean(properties, "logging.unregistered", true);

        return new DockBridgeConfig(
                dockerEndpoint,
                dockerPoll,
                proxyGroup,
                enablePing,
                pingInterval,
                maxFailures,
                autoLabelKey,
                autoLabelValue,
                autoNameLabel,
                autoPortLabel,
                duplicateStrategy,
                logScan,
                logMatches,
                logSummary,
                logSummaryWhenUnchanged,
                logRegistered,
                logUpdated,
                logUnregistered
        );
    }

    private static int readInt(Properties properties, String key, int defaultValue, Logger logger) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            logger.warn("Invalid integer for {}: {} (using default {})", key, raw, defaultValue);
            return defaultValue;
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean defaultValue) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    public String dockerEndpoint() {
        return dockerEndpoint;
    }

    public int dockerPollIntervalSeconds() {
        return dockerPollIntervalSeconds;
    }

    public String proxyGroup() {
        return proxyGroup;
    }

    public boolean healthEnablePing() {
        return healthEnablePing;
    }

    public int healthPingIntervalSeconds() {
        return healthPingIntervalSeconds;
    }

    public int healthMaxFailures() {
        return healthMaxFailures;
    }

    public String autoRegisterLabelKey() {
        return autoRegisterLabelKey;
    }

    public String autoRegisterLabelValue() {
        return autoRegisterLabelValue;
    }

    public String autoRegisterNameLabel() {
        return autoRegisterNameLabel;
    }

    public String autoRegisterPortLabel() {
        return autoRegisterPortLabel;
    }

    public String duplicateStrategy() {
        return duplicateStrategy;
    }

    public boolean logScan() {
        return logScan;
    }

    public boolean logMatches() {
        return logMatches;
    }

    public boolean logSummary() {
        return logSummary;
    }

    public boolean logSummaryWhenUnchanged() {
        return logSummaryWhenUnchanged;
    }

    public boolean logRegistered() {
        return logRegistered;
    }

    public boolean logUpdated() {
        return logUpdated;
    }

    public boolean logUnregistered() {
        return logUnregistered;
    }
}
