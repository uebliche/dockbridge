package net.uebliche.dockbridge;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Minimal Docker integration to auto-register containers exposing a matching label.
 */
public final class DockerService {

    private final ProxyServer server;
    private final Logger logger;
    private final DockBridgeConfig config;
    private final DockerClient dockerClient;
    private final DuplicateStrategy duplicateStrategy;
    private final Set<String> registeredNames = new HashSet<>();
    private List<Registration> lastRegistrations = List.of();
    private int lastMatchedCount = 0;
    private Instant lastScan = Instant.EPOCH;

    public DockerService(ProxyServer server, Logger logger, DockBridgeConfig config) {
        this.server = server;
        this.logger = logger;
        this.config = config;
        this.dockerClient = buildClient(config.dockerEndpoint());
        this.duplicateStrategy = DuplicateStrategy.from(config.duplicateStrategy(), logger);
    }

    public void initialize() {
        logger.info("DockerService initialized with endpoint {}", config.dockerEndpoint());
        try {
            dockerClient.pingCmd().exec();
            logger.info("Docker ping successful for endpoint {}", config.dockerEndpoint());
        } catch (Exception e) {
            logger.warn("Docker ping failed for endpoint {}: {}", config.dockerEndpoint(), e.getMessage());
        }
    }

    public void refreshContainers() {
        logger.info("Scanning Docker for auto-register containers using label {}={}.",
                config.autoRegisterLabelKey(), config.autoRegisterLabelValue());
        List<Container> containers = listMatchingContainers();
        lastScan = Instant.now();
        if (containers.isEmpty()) {
            logger.warn("No Docker containers found with label {}={}.",
                    config.autoRegisterLabelKey(), config.autoRegisterLabelValue());
            // Unregister anything we had before.
            unregisterMissing(Set.of());
            return;
        }

        logger.info("Found {} matching container(s).", containers.size());
        Set<String> seenNames = new HashSet<>();
        Map<String, Registration> newRegistrations = new HashMap<>();
        int registeredCount = 0;
        for (Container container : containers) {
            Registration registration = registerContainer(container, seenNames);
            if (registration != null) {
                seenNames.add(registration.serverName());
                newRegistrations.put(registration.serverName(), registration);
                registeredCount++;
            }
        }

        int unregisteredCount = unregisterMissing(seenNames);
        registeredNames.clear();
        registeredNames.addAll(seenNames);
        lastRegistrations = new ArrayList<>(newRegistrations.values());
        lastMatchedCount = containers.size();
        logger.info("Docker refresh complete: matched={}, registered/updated={}, unregistered={}.",
                containers.size(), registeredCount, unregisteredCount);
    }

    private List<Container> listMatchingContainers() {
        try {
            ListContainersCmd cmd = dockerClient.listContainersCmd()
                    .withShowAll(false)
                    .withLabelFilter(Map.of(config.autoRegisterLabelKey(), config.autoRegisterLabelValue()));
            List<Container> result = cmd.exec();
            if (result != null && !result.isEmpty()) {
                for (Container c : result) {
                    logger.info("Matched container id={} names={} labels={}",
                            c.getId().substring(0, 12),
                            c.getNames() == null ? "[]" : String.join(",", c.getNames()),
                            c.getLabels());
                }
            } else {
                logger.info("Docker returned no containers for the filter ({}={}).",
                        config.autoRegisterLabelKey(), config.autoRegisterLabelValue());
            }
            return result;
        } catch (Exception e) {
            logger.warn("Docker listContainers call failed (endpoint {}): {}", config.dockerEndpoint(), e.getMessage());
            return List.of();
        }
    }

    private Registration registerContainer(Container container, Set<String> seenNames) {
        String baseName = resolveServerName(container);
        String serverName = toUniqueName(baseName, container, seenNames);
        if (!serverName.equals(baseName)) {
            logger.info("Adjusted server name from {} to {} to ensure uniqueness.", baseName, serverName);
        }
        int port = resolvePort(container);
        String host = resolveHost(container);

        InetSocketAddress address = new InetSocketAddress(host, port);
        ServerInfo info = new ServerInfo(serverName, address);

        Optional<RegisteredServer> existing = server.getServer(serverName);
        if (existing.isPresent()) {
            server.unregisterServer(info);
            logger.info("Updated existing server {} -> {}:{}", serverName, address.getHostString(), address.getPort());
        } else {
            logger.info("Registering server {} at {}:{}", serverName, address.getHostString(), address.getPort());
        }
        try {
            server.registerServer(info);
        } catch (Exception ex) {
            logger.warn("Failed to register server {} at {}:{}: {}", serverName, address.getHostString(), address.getPort(), ex.getMessage());
            return null;
        }
        ensureTryIncludes(serverName);
        logger.info("Registration complete for server {} -> {}:{}.", serverName, address.getHostString(), address.getPort());
        return new Registration(serverName, host, port, shortContainerId(container), baseName);
    }

    private String toUniqueName(String baseName, Container container, Set<String> seenNames) {
        String candidate = baseName;
        if (duplicateStrategy == DuplicateStrategy.OVERWRITE) {
            return candidate;
        }

        String suffix = container.getId() != null && container.getId().length() >= 6
                ? container.getId().substring(0, 6)
                : String.valueOf(System.nanoTime());

        if (seenNames.contains(candidate) || registeredNames.contains(candidate) || server.getServer(candidate).isPresent()) {
            candidate = baseName + "-" + suffix;
        }
        // If still not unique (extremely unlikely), append timestamp.
        if (seenNames.contains(candidate) || registeredNames.contains(candidate) || server.getServer(candidate).isPresent()) {
            candidate = baseName + "-" + suffix + "-" + System.currentTimeMillis();
        }
        return candidate;
    }

    private String shortContainerId(Container container) {
        if (container.getId() == null) {
            return "unknown";
        }
        return container.getId().substring(0, Math.min(12, container.getId().length()));
    }

    public List<Registration> getCurrentRegistrations() {
        return List.copyOf(lastRegistrations);
    }

    public int getLastMatchedCount() {
        return lastMatchedCount;
    }

    public DockBridgeConfig getConfig() {
        return config;
    }

    public Instant getLastScan() {
        return lastScan;
    }

    public record Registration(String serverName, String host, int port, String containerId, String baseName) {
    }

    private enum DuplicateStrategy {
        SUFFIX,
        OVERWRITE;

        static DuplicateStrategy from(String raw, Logger logger) {
            if (raw == null) {
                return SUFFIX;
            }
            return switch (raw.trim().toLowerCase()) {
                case "suffix" -> SUFFIX;
                case "overwrite" -> OVERWRITE;
                default -> {
                    logger.warn("Unknown duplicate strategy '{}', defaulting to 'suffix'.", raw);
                    yield SUFFIX;
                }
            };
        }
    }

    private String resolveServerName(Container container) {
        Map<String, String> labels = container.getLabels();
        String labelName = labels.get(config.autoRegisterNameLabel());
        if (labelName != null && !labelName.isBlank()) {
            return labelName.trim();
        }
        String[] names = container.getNames();
        if (names != null && names.length > 0) {
            String raw = names[0];
            return raw.startsWith("/") ? raw.substring(1) : raw;
        }
        return container.getId().substring(0, 12);
    }

    private int resolvePort(Container container) {
        Map<String, String> labels = container.getLabels();
        String portLabel = labels.get(config.autoRegisterPortLabel());
        if (portLabel != null && !portLabel.isBlank()) {
            try {
                return Integer.parseInt(portLabel.trim());
            } catch (NumberFormatException ex) {
                logger.warn("Invalid port label {}={} on container {}. Falling back to exposed ports.",
                        config.autoRegisterPortLabel(), portLabel, container.getId());
            }
        }
        ContainerPort[] ports = container.getPorts();
        if (ports != null && ports.length > 0) {
            int privatePort = ports[0].getPrivatePort();
            if (privatePort > 0) {
                return privatePort;
            }
        }
        return 25565;
    }

    private String resolveHost(Container container) {
        String[] names = container.getNames();
        if (names != null && names.length > 0) {
            String raw = names[0];
            return raw.startsWith("/") ? raw.substring(1) : raw;
        }
        return "localhost";
    }

    private void unregisterServer(String serverName) {
        Optional<RegisteredServer> existing = server.getServer(serverName);
        if (existing.isEmpty()) {
            registeredNames.remove(serverName);
            return;
        }
        server.unregisterServer(existing.get().getServerInfo());
        registeredNames.remove(serverName);
        logger.info("Unregistered server {} (no matching container).", serverName);
    }

    private int unregisterMissing(Set<String> seenNames) {
        int removed = 0;
        Set<String> currentNames = new HashSet<>(registeredNames);
        for (String name : currentNames) {
            if (!seenNames.contains(name)) {
                unregisterServer(name);
                removed++;
            }
        }
        return removed;
    }

    private void ensureTryIncludes(String serverName) {
        var order = server.getConfiguration().getAttemptConnectionOrder();
        if (order.contains(serverName)) {
            return;
        }
        try {
            order.add(serverName);
            logger.info("Added {} to connection order list.", serverName);
        } catch (UnsupportedOperationException ex) {
            logger.warn("Could not update connection order at runtime. Please ensure '{}' is present in the 'try' list of velocity.toml.", serverName);
        }
    }

    private DockerClient buildClient(String endpoint) {
        DefaultDockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(endpoint)
                .build();
        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .sslConfig(clientConfig.getSSLConfig())
                .build();
        return DockerClientImpl.getInstance(clientConfig, httpClient);
    }
}
