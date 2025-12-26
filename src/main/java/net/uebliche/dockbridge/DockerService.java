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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
        if (config.logScan()) {
            logger.info("Scanning Docker for auto-register containers using label {}={}.",
                    config.autoRegisterLabelKey(), config.autoRegisterLabelValue());
        }
        List<Container> containers = listMatchingContainers();
        lastScan = Instant.now();
        if (containers.isEmpty()) {
            // Unregister anything we had before.
            int unregisteredCount = unregisterMissing(Set.of());
            registeredNames.clear();
            lastRegistrations = List.of();
            lastMatchedCount = 0;
            if (config.logSummary() && (unregisteredCount > 0 || config.logSummaryWhenUnchanged())) {
                logger.info("Docker refresh complete: matched=0, registered=0, updated=0, unchanged=0, unregistered={}.",
                        unregisteredCount);
            }
            return;
        }

        containers = new ArrayList<>(containers);
        containers.sort(Comparator.comparing(Container::getId, Comparator.nullsLast(String::compareTo)));

        Map<String, Registration> previousByName = lastRegistrations.stream()
                .collect(Collectors.toMap(Registration::serverName, registration -> registration, (a, b) -> a));
        Map<String, Registration> previousByContainer = lastRegistrations.stream()
                .collect(Collectors.toMap(Registration::containerId, registration -> registration, (a, b) -> a));
        Map<String, List<Container>> nameGroups = containers.stream()
                .collect(Collectors.groupingBy(this::resolveServerName));

        Set<String> seenNames = new HashSet<>();
        Map<String, Registration> newRegistrations = new HashMap<>();
        int registeredCount = 0;
        int updatedCount = 0;
        int unchangedCount = 0;
        for (Container container : containers) {
            String baseName = resolveServerName(container);
            String serverName = chooseServerName(container, baseName, nameGroups, previousByName, previousByContainer, seenNames);
            RegistrationOutcome outcome = registerContainer(container, baseName, serverName);
            if (outcome == null) {
                continue;
            }
            Registration registration = outcome.registration();
            seenNames.add(registration.serverName());
            newRegistrations.put(registration.serverName(), registration);
            switch (outcome.status()) {
                case REGISTERED -> registeredCount++;
                case UPDATED -> updatedCount++;
                case UNCHANGED -> unchangedCount++;
            }
        }

        int unregisteredCount = unregisterMissing(seenNames);
        registeredNames.clear();
        registeredNames.addAll(seenNames);
        lastRegistrations = new ArrayList<>(newRegistrations.values());
        lastMatchedCount = containers.size();
        if (config.logSummary() && (registeredCount > 0 || updatedCount > 0 || unregisteredCount > 0 || config.logSummaryWhenUnchanged())) {
            logger.info("Docker refresh complete: matched={}, registered={}, updated={}, unchanged={}, unregistered={}.",
                    containers.size(), registeredCount, updatedCount, unchangedCount, unregisteredCount);
        }
    }

    private List<Container> listMatchingContainers() {
        try {
            ListContainersCmd cmd = dockerClient.listContainersCmd()
                    .withShowAll(false)
                    .withLabelFilter(Map.of(config.autoRegisterLabelKey(), config.autoRegisterLabelValue()));
            List<Container> result = cmd.exec();
            if (result != null && !result.isEmpty() && config.logMatches()) {
                for (Container c : result) {
                    logger.info("Matched container id={} names={}",
                            shortContainerId(c),
                            c.getNames() == null ? "[]" : String.join(",", c.getNames()));
                }
            }
            return result;
        } catch (Exception e) {
            logger.warn("Docker listContainers call failed (endpoint {}): {}", config.dockerEndpoint(), e.getMessage());
            return List.of();
        }
    }

    private RegistrationOutcome registerContainer(Container container, String baseName, String serverName) {
        int port = resolvePort(container);
        String host = resolveHost(container);

        InetSocketAddress address = new InetSocketAddress(host, port);
        ServerInfo info = new ServerInfo(serverName, address);

        Optional<RegisteredServer> existing = server.getServer(serverName);
        if (existing.isPresent()) {
            InetSocketAddress existingAddress = existing.get().getServerInfo().getAddress();
            if (addressesMatch(existingAddress, address)) {
                ensureTryIncludes(serverName);
                return new RegistrationOutcome(
                        new Registration(serverName, host, port, shortContainerId(container), baseName),
                        RegistrationStatus.UNCHANGED);
            }
            server.unregisterServer(existing.get().getServerInfo());
            try {
                server.registerServer(info);
            } catch (Exception ex) {
                logger.warn("Failed to update server {} at {}:{}: {}", serverName, address.getHostString(), address.getPort(), ex.getMessage());
                return null;
            }
            ensureTryIncludes(serverName);
            if (config.logUpdated()) {
                logger.info("Updated server {} -> {}:{}.", serverName, address.getHostString(), address.getPort());
            }
            return new RegistrationOutcome(
                    new Registration(serverName, host, port, shortContainerId(container), baseName),
                    RegistrationStatus.UPDATED);
        }
        try {
            server.registerServer(info);
        } catch (Exception ex) {
            logger.warn("Failed to register server {} at {}:{}: {}", serverName, address.getHostString(), address.getPort(), ex.getMessage());
            return null;
        }
        ensureTryIncludes(serverName);
        if (config.logRegistered()) {
            logger.info("Registered server {} -> {}:{}.", serverName, address.getHostString(), address.getPort());
        }
        return new RegistrationOutcome(
                new Registration(serverName, host, port, shortContainerId(container), baseName),
                RegistrationStatus.REGISTERED);
    }

    private String chooseServerName(
            Container container,
            String baseName,
            Map<String, List<Container>> nameGroups,
            Map<String, Registration> previousByName,
            Map<String, Registration> previousByContainer,
            Set<String> seenNames
    ) {
        if (duplicateStrategy == DuplicateStrategy.OVERWRITE) {
            return baseName;
        }

        String containerKey = shortContainerId(container);
        Registration previous = previousByContainer.get(containerKey);
        if (previous != null && !seenNames.contains(previous.serverName())) {
            return previous.serverName();
        }

        List<Container> group = nameGroups.getOrDefault(baseName, List.of());
        int groupSize = group.size();
        String suffix = shortContainerSuffix(container, 6);
        boolean isPrimary = groupSize > 0 && group.get(0) == container;
        String candidate = groupSize > 1
                ? (isPrimary ? baseName : baseName + "-" + suffix)
                : baseName;

        candidate = ensureUniqueName(candidate, baseName, suffix, previousByName, seenNames, containerKey);
        return candidate;
    }

    private String shortContainerId(Container container) {
        if (container.getId() == null) {
            return "unknown";
        }
        return container.getId().substring(0, Math.min(12, container.getId().length()));
    }

    private String shortContainerSuffix(Container container, int length) {
        String id = shortContainerId(container);
        return id.substring(0, Math.min(length, id.length()));
    }

    private String ensureUniqueName(
            String candidate,
            String baseName,
            String suffix,
            Map<String, Registration> previousByName,
            Set<String> seenNames,
            String containerKey
    ) {
        String current = candidate;
        if (nameConflicts(current, previousByName, seenNames, containerKey)) {
            String withSuffix = baseName + "-" + suffix;
            if (!nameConflicts(withSuffix, previousByName, seenNames, containerKey)) {
                return withSuffix;
            }
            current = withSuffix;
        }

        int counter = 1;
        while (nameConflicts(current, previousByName, seenNames, containerKey)) {
            current = baseName + "-" + suffix + "-" + counter;
            counter++;
        }
        return current;
    }

    private boolean nameConflicts(
            String name,
            Map<String, Registration> previousByName,
            Set<String> seenNames,
            String containerKey
    ) {
        if (seenNames.contains(name)) {
            return true;
        }
        Registration previous = previousByName.get(name);
        if (server.getServer(name).isPresent()) {
            return previous == null || !previous.containerId().equals(containerKey);
        }
        return false;
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
        if (config.logUnregistered()) {
            logger.info("Unregistered server {} (no matching container).", serverName);
        }
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
            if (config.logRegistered() || config.logUpdated()) {
                logger.info("Added {} to connection order list.", serverName);
            }
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

    private boolean addressesMatch(InetSocketAddress existing, InetSocketAddress desired) {
        if (existing == null || desired == null) {
            return false;
        }
        return existing.getPort() == desired.getPort()
                && existing.getHostString().equalsIgnoreCase(desired.getHostString());
    }

    private enum RegistrationStatus {
        REGISTERED,
        UPDATED,
        UNCHANGED
    }

    private record RegistrationOutcome(Registration registration, RegistrationStatus status) {
    }
}
