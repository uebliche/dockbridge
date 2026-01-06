---
layout: home
hero:
  name: DockBridge
  text: Auto-register Docker servers in Velocity
  tagline: Discover containers, register servers, and track updates.
  image:
    src: /images/dockbridge-hero-placeholder.svg
    alt: DockBridge preview
  actions:
    - text: Install
      link: /dockbridge/#install
    - text: Commands
      link: /dockbridge/#commands-and-permissions
    - text: Download (Modrinth)
      link: https://modrinth.com/project/dockbridge
features:
  - title: Auto discovery
    details: Register servers from Docker labels automatically.
  - title: Safe naming
    details: Suffix or overwrite strategies for collisions.
  - title: Update hints
    details: Optional Modrinth update notices on login.
---

## Install

1) Download the DockBridge jar from Modrinth.
2) Drop it into the Velocity `plugins/` folder.
3) If Velocity runs in Docker, mount `/var/run/docker.sock`.
4) Start Velocity to generate `dockbridge.conf`.

## Configuration

`dockbridge.conf` (created on first start):

```
docker.endpoint=unix:///var/run/docker.sock
docker.poll_interval_seconds=30
filters.proxy_group=default
docker.autoregister.label_key=net.uebliche.dockbridge.autoregister
docker.autoregister.label_value=true
docker.autoregister.name_label=net.uebliche.dockbridge.server_name
docker.autoregister.port_label=net.uebliche.dockbridge.server_port
docker.autoregister.duplicate_strategy=suffix
```

## Docker labels (example)

```
net.uebliche.dockbridge.autoregister=true
net.uebliche.dockbridge.server_name=limbo
net.uebliche.dockbridge.server_port=30000
```

## Commands and permissions

- `/dockbridge` (permission `dockbridge.command`)
- Update hint on login (permission `dockbridge.update.notify`)

## Local testing

```
./gradlew runVelocity
```

Or Docker compose:

```
docker compose up --build
```
