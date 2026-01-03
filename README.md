# DockBridge

Velocity plugin to auto-register Docker-backed servers (e.g., Limbo) and check for updates on Modrinth.

## Features
- Auto-discovery: Containers with label `net.uebliche.dockbridge.autoregister=true` are registered as Velocity servers. Host from container name, port from label `net.uebliche.dockbridge.server_port` (fallback: first exposed port, else 25565).
- Name collisions: Default `suffix` → append short container id (`basename-abcdef`). Alternative `overwrite` → keep basename, last writer wins.
- `/dockbridge` command (permission `dockbridge.command`): Shows label filter, duplicate strategy, last scan stats, and registered servers (no sensitive data).
- Modrinth update check on proxy start; login hint for players with permission `dockbridge.update.notify`.

## Installation
1) Download the latest DockBridge release from Modrinth (jar file).
2) Place the jar into your Velocity `plugins/` folder.
3) (Optional) If Velocity runs in Docker, mount `/var/run/docker.sock` into the Velocity container so DockBridge can discover containers.
4) Start Velocity. On first start, the config file `dockbridge.conf` is created in the Velocity data folder; adjust settings and restart.
5) Permissions:
   - `dockbridge.command` for `/dockbridge`
   - `dockbridge.update.notify` for update hints on login

## Configuration
`dockbridge.conf` (created in the Velocity data folder on first start):
```
docker.endpoint=unix:///var/run/docker.sock
docker.poll_interval_seconds=30
filters.proxy_group=default
docker.autoregister.label_key=net.uebliche.dockbridge.autoregister
docker.autoregister.label_value=true
docker.autoregister.name_label=net.uebliche.dockbridge.server_name
docker.autoregister.port_label=net.uebliche.dockbridge.server_port
docker.autoregister.duplicate_strategy=suffix   # suffix | overwrite
```

## Docker labels (example)
```
net.uebliche.dockbridge.autoregister=true
net.uebliche.dockbridge.server_name=limbo
net.uebliche.dockbridge.server_port=30000
```

<!-- modrinth_exclude.start -->
## Local testing
### Gradle run
```
./gradlew runVelocity
```
Downloads Velocity, builds the plugin (shadowJar), and starts the proxy with the plugin loaded.

### Docker Compose (Limbo set)
```
docker compose up --build        # or: docker compose -f docker-compose.limbo.yml up --build
```
Includes three Limbo backends (ports 30000/30001/30002) and Velocity on host port 26678. The container builds the shaded plugin jar and runs a real Velocity jar with the plugin loaded, while still mounting your sources/config.
<!-- modrinth_exclude.end -->

## Commands & permissions
- `/dockbridge` (permission `dockbridge.command`): Status and registered servers.
- Update hint on login: permission `dockbridge.update.notify`.

## Troubleshooting
- No servers registered?  
  - Ensure your containers have the label `net.uebliche.dockbridge.autoregister=true` and a valid `net.uebliche.dockbridge.server_port`.  
  - Make sure Velocity can reach Docker: mount `/var/run/docker.sock` (or set `docker.endpoint` to a reachable TCP endpoint).  
  - Check `/dockbridge` output for last scan count and registered servers.
- Port mismatch or forwarding errors?  
  - Verify the Limbo (or other backend) port matches the label and the server properties.  
  - For Velocity forwarding, ensure forwarding secret/mode match on both ends.
- Duplicate names?  
  - Default strategy is `suffix`. Switch to `overwrite` via `docker.autoregister.duplicate_strategy` if you want last-writer-wins.

<!-- modrinth_exclude.start -->
## Build
```
./gradlew build
```
Produces the shaded jar at `build/libs/DockBridge-<version>.jar`.

## Contributing
- Fork & PRs welcome. Keep code comments minimal but purposeful.
- Default Java 21, Gradle Kotlin DSL, Velocity API 3.4.0-SNAPSHOT for run task; 3.1.1 for compileOnly/annotationProcessor.
- Run `./gradlew build` before submitting; Docker-based tests are optional.
<!-- modrinth_exclude.end -->
