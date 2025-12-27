package net.uebliche.dockbridge;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Plugin(
        id = "dockbridge",
        name = "DockBridge",
        version = BuildConstants.VERSION,
        authors = {"übliche"}
)
public final class DockBridgePlugin {

    public static final String UPDATE_NOTIFY_PERMISSION = "dockbridge.update.notify";
    public static final String COMMAND_PERMISSION = "dockbridge.command";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final UpdateChecker updateChecker;
    private final AtomicReference<String> latestVersion = new AtomicReference<>();
    private DockBridgeConfig config;
    private DockerService dockerService;

    @Inject
    public DockBridgePlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.updateChecker = new UpdateChecker(logger);
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("DockBridge initializing...");
        this.config = DockBridgeConfig.load(dataDirectory, logger);
        logger.info("Loaded DockBridge configuration (proxy group: {}).", config.proxyGroup());

        this.dockerService = new DockerService(server, logger, config);
        dockerService.initialize();
        try {
            dockerService.refreshContainers();
        } catch (Exception ex) {
            logger.warn("Initial Docker refresh failed: {}", ex.getMessage());
        }
        registerCommands();
        logger.info("Scheduled Docker refresh every {}s.", config.dockerPollIntervalSeconds());
        server.getScheduler()
                .buildTask(this, dockerService::refreshContainers)
                .delay(Duration.ofSeconds(config.dockerPollIntervalSeconds()))
                .repeat(Duration.ofSeconds(config.dockerPollIntervalSeconds()))
                .schedule();

        String currentVersion = resolveCurrentVersion();
        logger.info("DockBridge starting with version {}.", currentVersion);

        server.getScheduler()
                .buildTask(this, () -> runUpdateCheck(currentVersion))
                .schedule();
    }

    @Subscribe
    public void onPlayerLogin(PostLoginEvent event) {
        String available = latestVersion.get();
        if (available == null) {
            return;
        }

        if (event.getPlayer().hasPermission(UPDATE_NOTIFY_PERMISSION)) {
            event.getPlayer().sendMessage(Component.text("[DockBridge] A new version is available: " + available));
        }
    }

    private void runUpdateCheck(String currentVersion) {
        Optional<String> update = updateChecker.checkForUpdate(currentVersion);
        if (update.isEmpty()) {
            logger.info("DockBridge is up to date ({}).", currentVersion);
            return;
        }

        latestVersion.set(update.get());
        logger.info("A new DockBridge version is available: {}", update.get());
    }

    private String resolveCurrentVersion() {
        return server.getPluginManager()
                .getPlugin("dockbridge")
                .flatMap(container -> container.getDescription().getVersion())
                .orElse("unknown");
    }

    private void registerCommands() {
        var manager = server.getCommandManager();
        manager.register(
                manager.metaBuilder("dockbridge")
                        .plugin(this)
                        .build(),
                new DockBridgeCommand(server, dockerService));
    }
}
