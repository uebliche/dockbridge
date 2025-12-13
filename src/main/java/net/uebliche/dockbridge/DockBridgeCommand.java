package net.uebliche.dockbridge;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;

/**
 * Provides a simple information dump about currently registered Docker-backed servers.
 */
public final class DockBridgeCommand implements SimpleCommand {

    private final ProxyServer server;
    private final DockerService dockerService;

    public DockBridgeCommand(ProxyServer server, DockerService dockerService) {
        this.server = server;
        this.dockerService = dockerService;
    }

    @Override
    public void execute(Invocation invocation) {
        var source = invocation.source();

        if (!source.hasPermission(DockBridgePlugin.COMMAND_PERMISSION)) {
            source.sendMessage(Component.text("[DockBridge] You do not have permission to use this command.", NamedTextColor.RED));
            return;
        }

        int totalPlugins = server.getPluginManager().getPlugins().size();
        List<DockerService.Registration> registrations = dockerService.getCurrentRegistrations();
        int matched = dockerService.getLastMatchedCount();

        source.sendMessage(prefix("Plugins loaded: ", String.valueOf(totalPlugins)));
        source.sendMessage(prefix("Docker label filter: ",
                dockerService.getConfig().autoRegisterLabelKey() + "=" + dockerService.getConfig().autoRegisterLabelValue()));
        source.sendMessage(prefix("Duplicate strategy: ", dockerService.getConfig().duplicateStrategy()));
        source.sendMessage(prefix("Last scan matched ", matched + " container(s); registered " + registrations.size() + " server(s)."));

        if (registrations.isEmpty()) {
            source.sendMessage(Component.text("[DockBridge] No registered Docker servers.", NamedTextColor.YELLOW));
            return;
        }

        source.sendMessage(Component.text("[DockBridge] Registered servers:", NamedTextColor.GOLD));
        for (DockerService.Registration reg : registrations) {
            Component line = Component.text(" - ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(reg.serverName(), NamedTextColor.AQUA))
                    .append(Component.text(" -> ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(reg.host() + ":" + reg.port(), NamedTextColor.GREEN))
                    .append(Component.text(" (", NamedTextColor.DARK_GRAY))
                    .append(Component.text("container ", NamedTextColor.GRAY))
                    .append(Component.text(reg.containerId(), NamedTextColor.WHITE))
                    .append(Component.text(", base ", NamedTextColor.GRAY))
                    .append(Component.text(reg.baseName(), NamedTextColor.WHITE))
                    .append(Component.text(")", NamedTextColor.DARK_GRAY));
            source.sendMessage(line);
        }
    }

    private Component prefix(String label, String value) {
        return Component.text("[DockBridge] ", NamedTextColor.GOLD)
                .append(Component.text(label, NamedTextColor.GRAY))
                .append(Component.text(value, NamedTextColor.WHITE));
    }
}
